package com.abavava.chat.http;

import com.abavava.chat.domain.AbvException;
import com.abavava.chat.observability.BusinessMetrics;
import com.abavava.chat.security.JwtService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/**
 * Проверка Bearer access-токена на защищённых роутах: кладёт id пользователя
 * в атрибут запроса (см. Httpx.USER_ID_ATTRIBUTE). Аналог auth.Middleware в Go.
 *
 * <p>Отказы считаются в {@code ababawa_security_access_denied_total}
 * (channel=http): частые отказы на защищённых эндпоинтах — признак перебора
 * токенов.
 */
public class AccessTokenFilter implements Filter {

    /** Публичные пути под /api/v1: аутентификация, health, WebSocket и временный тест почты. */
    private static final String[] PUBLIC_PREFIXES = {
            "/api/v1/auth/",
            "/api/v1/healthz",
            "/api/v1/readyz",
            "/api/v1/ws",
            // Временный MailTestController: токена ещё нет, почту настраивают до регистрации.
            "/api/v1/mail/"
    };

    private final JwtService jwtService;
    private final ObjectMapper mapper;
    private final BusinessMetrics metrics;

    public AccessTokenFilter(JwtService jwtService, ObjectMapper mapper, BusinessMetrics metrics) {
        this.jwtService = jwtService;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) || isPublicPath(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String token = Httpx.bearerToken(req);
        if (token == null) {
            metrics.accessDenied("http", "missing_token");
            Httpx.writeError(res, AbvException.unauthorized("authorization header is missing"), mapper);
            return;
        }
        try {
            UUID userId = jwtService.verifyAccessToken(token);
            req.setAttribute(Httpx.USER_ID_ATTRIBUTE, userId);
        } catch (AbvException e) {
            metrics.accessDenied("http", "invalid_token");
            Httpx.writeError(res, e, mapper);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isPublicPath(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
