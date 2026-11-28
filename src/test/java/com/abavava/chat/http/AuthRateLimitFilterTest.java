package com.abavava.chat.http;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.observability.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limit `/auth/*` и `/mail/*`: ключ окна — IP клиента от
 * {@link ClientIpResolver}, а не подставленный заголовок.
 *
 * <p>Проверяется связка, которую нельзя проверить по отдельности: подмена
 * `X-Forwarded-For` от прямого соединения не создаёт новое окно, а за
 * доверенным прокси у каждого клиента своё окно. Отклонение считается в
 * {@code ababawa_security_rate_limited_total} с меткой scope.
 */
class AuthRateLimitFilterTest {

    private static final String TRUSTED_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    /** Адрес, с которого приложение видит host-Nginx (docker bridge). */
    private static final String PROXY = "172.17.0.1";

    private final AtomicInteger passed = new AtomicInteger();
    private SimpleMeterRegistry registry;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        AbvProperties props = loadProps("abv.auth-rate-limit.limit", "2");
        registry = new SimpleMeterRegistry();
        filter = new AuthRateLimitFilter(props, new ObjectMapper(),
                ClientIpResolver.parse(TRUSTED_PROXIES), new BusinessMetrics(registry));
    }

    @Test
    void rejectsThirdRequestFromSameClientBehindTrustedProxy() throws Exception {
        assertThat(call(request(PROXY, "203.0.113.7", "/api/v1/auth/login"))).isEqualTo(200);
        assertThat(call(request(PROXY, "203.0.113.7", "/api/v1/auth/login"))).isEqualTo(200);
        assertThat(call(request(PROXY, "203.0.113.7", "/api/v1/auth/login"))).isEqualTo(429);

        assertThat(passed).hasValue(2);
        assertThat(counter("auth")).isEqualTo(1.0);
    }

    @Test
    void eachClientBehindProxyHasItsOwnWindow() throws Exception {
        call(request(PROXY, "203.0.113.7", "/api/v1/auth/login"));
        call(request(PROXY, "203.0.113.7", "/api/v1/auth/login"));
        // Другой клиент за тем же прокси не попадает в чужое окно.
        assertThat(call(request(PROXY, "198.51.100.9", "/api/v1/auth/login"))).isEqualTo(200);
        assertThat(call(request(PROXY, "198.51.100.9", "/api/v1/auth/login"))).isEqualTo(200);
        // Первый клиент уже исчерпал лимит.
        assertThat(call(request(PROXY, "203.0.113.7", "/api/v1/auth/login"))).isEqualTo(429);

        assertThat(passed).hasValue(4);
        assertThat(counter("auth")).isEqualTo(1.0);
    }

    @Test
    void spoofedForwardedForFromDirectConnectionCannotResetTheWindow() throws Exception {
        // Пир не из доверенных сетей: X-Forwarded-For игнорируется целиком,
        // поэтому разные «адреса» в заголовке не дают новых окон.
        assertThat(call(request("203.0.113.7", "10.0.0.5", "/api/v1/auth/login"))).isEqualTo(200);
        assertThat(call(request("203.0.113.7", "10.0.0.6", "/api/v1/auth/login"))).isEqualTo(200);
        assertThat(call(request("203.0.113.7", "10.0.0.7", "/api/v1/auth/login"))).isEqualTo(429);

        assertThat(passed).hasValue(2);
        assertThat(counter("auth")).isEqualTo(1.0);
    }

    @Test
    void countsMailScopeSeparately() throws Exception {
        call(request(PROXY, "203.0.113.7", "/api/v1/mail/test"));
        call(request(PROXY, "203.0.113.7", "/api/v1/mail/test"));
        assertThat(call(request(PROXY, "203.0.113.7", "/api/v1/mail/test"))).isEqualTo(429);

        assertThat(counter("mail")).isEqualTo(1.0);
        assertThat(counter("auth")).isZero();
    }

    private int call(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
        return response.getStatus();
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private double counter(String scope) {
        var counter = registry.find("ababawa.security.rate_limited").tag("scope", scope).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static AbvProperties loadProps(String... overrides) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        ConfigurationPropertySource base = ConfigurationPropertySources.from(sources).iterator().next();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < overrides.length; i += 2) {
            values.put(overrides[i], overrides[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(values), base)
                .bind("abv", Bindable.of(AbvProperties.class))
                .orElseThrow(() -> new AssertionError("abv section is missing in application.yml"));
    }
}
