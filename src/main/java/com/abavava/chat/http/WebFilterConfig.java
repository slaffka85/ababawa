package com.abavava.chat.http;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.observability.BusinessMetrics;
import com.abavava.chat.security.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Порядок и области действия HTTP-фильтров. Порядок повторяет цепочку
 * httpx.Chain в Go: request-id, CORS, rate limit, авторизация.
 */
@Configuration
public class WebFilterConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(10);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(AbvProperties props) {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(props));
        bean.addUrlPatterns("/*");
        bean.setOrder(20);
        return bean;
    }

    /**
     * Единственный источник IP клиента для rate limit'а, бизнес-логики и
     * сохранения сессий: доверяет forwarded-заголовкам только от сетей
     * abv.trusted-proxies. Опечатка в списке сетей роняет старт контекста.
     */
    @Bean
    public ClientIpResolver clientIpResolver(AbvProperties props) {
        try {
            return ClientIpResolver.parse(props.trustedProxies());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "abv.trusted-proxies (ABV_TRUSTED_PROXIES) is invalid: " + e.getMessage(), e);
        }
    }

    @Bean
    public FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilter(AbvProperties props,
                                                                           ObjectMapper mapper,
                                                                           ClientIpResolver clientIp,
                                                                           BusinessMetrics metrics) {
        FilterRegistrationBean<AuthRateLimitFilter> bean =
                new FilterRegistrationBean<>(new AuthRateLimitFilter(props, mapper, clientIp, metrics));
        // /api/v1/mail/* — временный тест почты: шлёт письмо на любой адрес,
        // поэтому ограничен тем же окном, что и /auth/*.
        bean.addUrlPatterns("/api/v1/auth/*", "/api/v1/mail/*");
        bean.setOrder(30);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<AccessTokenFilter> accessTokenFilter(JwtService jwtService,
                                                                       ObjectMapper mapper,
                                                                       BusinessMetrics metrics) {
        FilterRegistrationBean<AccessTokenFilter> bean =
                new FilterRegistrationBean<>(new AccessTokenFilter(jwtService, mapper, metrics));
        bean.addUrlPatterns("/api/v1/*");
        bean.setOrder(40);
        return bean;
    }
}
