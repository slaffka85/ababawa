package com.abavava.chat.http;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Определение IP клиента за доверенным прокси.
 *
 * <p>Ключевые сценарии безопасности:
 * <ul>
 *   <li>прямое соединение (пир не из доверенных сетей) игнорирует любые
 *       forwarded-заголовки — подменить IP нельзя;</li>
 *   <li>за доверенным прокси цепочка читается справа налево, поэтому
 *       подставленный клиентом левый адрес отбрасывается;</li>
 *   <li>IPv4 и IPv6 обрабатываются одинаково, мусор в заголовке не ломает
 *       определение адреса.</li>
 * </ul>
 */
class ClientIpResolverTest {

    /** Штатная схема: loopback + приватные сети Docker. */
    private static final String DEFAULT_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    private static ClientIpResolver resolver() {
        return ClientIpResolver.parse(DEFAULT_PROXIES);
    }

    @Test
    void directConnectionIgnoresForwardedHeaders() {
        ClientIpResolver resolver = resolver();

        // Клиент пришёл напрямую и пытается выдать себя за другой адрес.
        assertThat(resolver.resolve("203.0.113.7", "10.0.0.5", "10.0.0.5", "for=10.0.0.5")).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedProxyAppendsClientAddressToForwardedFor() {
        ClientIpResolver resolver = resolver();

        // Nginx ($proxy_add_x_forwarded_for) дописал реальный адрес справа.
        assertThat(resolver.resolve("172.17.0.1", "203.0.113.7", null, null)).isEqualTo("203.0.113.7");
        // Несколько прокси: слева клиент, справа ближайший к приложению прокси.
        assertThat(resolver.resolve("172.17.0.1", "203.0.113.7, 198.51.100.9", null, null))
                .isEqualTo("198.51.100.9");
    }

    @Test
    void spoofedLeftEntryIsIgnoredBehindTrustedProxy() {
        ClientIpResolver resolver = resolver();

        // Клиент прислал X-Forwarded-For: 1.2.3.4, nginx дописал реальный адрес.
        // Раньше (первый элемент) это давало подмену IP и обход rate limit.
        assertThat(resolver.resolve("172.17.0.1", "1.2.3.4, 203.0.113.7", null, null))
                .isEqualTo("203.0.113.7");
        // Даже если подставленный адрес попадает в доверенную сеть, он не станет «пиром».
        assertThat(resolver.resolve("172.17.0.1", "10.1.2.3, 203.0.113.7", null, null))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void ipv4AndIpv6AreParsedAndCanonicalised() {
        ClientIpResolver resolver = resolver();

        assertThat(resolver.resolve("172.17.0.1", "203.0.113.7", null, null)).isEqualTo("203.0.113.7");
        // Пир и клиент по IPv6 (в т.ч. в скобках — формат X-Forwarded-For).
        assertThat(resolver.resolve("[::1]:8080", "[2001:db8::1]", null, null))
                .isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.resolve("0:0:0:0:0:0:0:1", "2001:db8::1", null, null))
                .isEqualTo("2001:db8:0:0:0:0:0:1");
        // IPv4-mapped IPv6 приводится к IPv4: доверенная сеть работает одинаково.
        assertThat(resolver.resolve("[::ffff:172.17.0.1]", "203.0.113.7", null, null))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void invalidForwardedValuesAreIgnored() {
        ClientIpResolver resolver = resolver();

        // "unknown", имя хоста, сокращённый IPv4 и мусор не считаются адресом.
        assertThat(resolver.resolve("172.17.0.1", "unknown", null, null)).isEqualTo("172.17.0.1");
        assertThat(resolver.resolve("172.17.0.1", "proxy.example.com", null, null)).isEqualTo("172.17.0.1");
        assertThat(resolver.resolve("172.17.0.1", "1.2.3", null, null)).isEqualTo("172.17.0.1");
        assertThat(resolver.resolve("172.17.0.1", "010.0.0.1", null, null)).isEqualTo("172.17.0.1");
        assertThat(resolver.resolve("172.17.0.1", "  ", null, null)).isEqualTo("172.17.0.1");
    }

    @Test
    void xRealIpIsUsedWhenForwardedForIsAbsent() {
        ClientIpResolver resolver = resolver();

        assertThat(resolver.resolve("172.17.0.1", null, "203.0.113.7", null)).isEqualTo("203.0.113.7");
        // X-Forwarded-For главнее X-Real-IP.
        assertThat(resolver.resolve("172.17.0.1", "198.51.100.9", "203.0.113.7", null))
                .isEqualTo("198.51.100.9");
        // От недоверенного пира X-Real-IP тоже игнорируется.
        assertThat(resolver.resolve("203.0.113.7", null, "10.0.0.5", null)).isEqualTo("203.0.113.7");
    }

    @Test
    void forwardedHeaderIsFallbackWhenForwardedForIsAbsent() {
        ClientIpResolver resolver = resolver();

        assertThat(resolver.resolve("172.17.0.1", null, null,
                "for=203.0.113.7;proto=https, for=\"[2001:db8::1]:443\""))
                .isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.resolve("172.17.0.1", null, null, "for=unknown"))
                .isEqualTo("172.17.0.1");
    }

    @Test
    void allTrustedChainReturnsLeftmostHop() {
        ClientIpResolver resolver = resolver();

        assertThat(resolver.resolve("172.17.0.1", "10.9.8.7, 10.1.1.1", null, null))
                .isEqualTo("10.9.8.7");
    }

    @Test
    void emptyTrustListNeverTrustsHeaders() {
        ClientIpResolver resolver = ClientIpResolver.parse("");

        assertThat(resolver.resolve("127.0.0.1", "203.0.113.7", "203.0.113.7", "for=203.0.113.7"))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void readsHeadersFromServletRequest() {
        ClientIpResolver resolver = resolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.17.0.1:54321");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");
        request.addHeader("X-Real-IP", "1.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void malformedTrustedProxyConfigurationFailsFast() {
        assertThatThrownBy(() -> ClientIpResolver.parse("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10.0.0.0/33");
        assertThatThrownBy(() -> ClientIpResolver.parse("not-an-ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-an-ip");
        assertThatThrownBy(() -> ClientIpResolver.parse("10.0.0.0/8/8"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
