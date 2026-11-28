package com.abavava.chat.http;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.observability.BusinessMetrics;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Скользящее окно в памяти процесса: защита /auth/* от перебора. Параметры
 * окна — abv.auth-rate-limit (по умолчанию 30 запросов за 60 с, как
 * httpx.RateLimit(30, time.Minute) в Go). При масштабировании заменяется
 * Redis-вариантом без изменения фильтра.
 *
 * <p>Ключ окна — IP клиента от {@link ClientIpResolver}: за доверенным прокси
 * это реальный адрес, а не подставленный клиентом. Каждое отклонение считается
 * в {@code ababawa_security_rate_limited_total} (без IP в метках).
 */
public class AuthRateLimitFilter implements Filter {

    /** Порог, после которого чистим окна без свежих попаданий. */
    private static final int PURGE_THRESHOLD = 10_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowSeconds;
    private final ObjectMapper mapper;
    private final ClientIpResolver clientIp;
    private final BusinessMetrics metrics;

    public AuthRateLimitFilter(AbvProperties props, ObjectMapper mapper,
                               ClientIpResolver clientIp, BusinessMetrics metrics) {
        this.limit = props.authRateLimit().limit();
        this.windowSeconds = props.authRateLimit().window().getSeconds();
        this.mapper = mapper;
        this.clientIp = clientIp;
        this.metrics = metrics;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String key = clientIp.resolve(req);
        long now = Instant.now().getEpochSecond();
        long cutoff = now - windowSeconds;
        if (!allow(key, now, cutoff)) {
            metrics.rateLimited(scopeOf(req));
            res.setHeader("Retry-After", Long.toString(windowSeconds));
            Httpx.writeError(res, new AbvException(AbvException.Code.RATE_LIMITED,
                    "too many requests, slow down", null, null), mapper);
            return;
        }
        purgeStale(cutoff);
        chain.doFilter(request, response);
    }

    /** Какую ручку ограничиваем — для метки {@code scope} метрики. */
    private static String scopeOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/v1/mail/") ? "mail" : "auth";
    }

    private boolean allow(String key, long now, long cutoff) {
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= cutoff) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * Чистка окон, в которых не осталось попаданий: иначе карта растёт вместе с
     * числом уникальных адресов (боты, сканеры) и течёт памятью. Вызывается
     * снаружи блокировок — только когда карта превысила {@link #PURGE_THRESHOLD}.
     */
    private void purgeStale(long cutoff) {
        if (hits.size() <= PURGE_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<String, Deque<Long>>> iterator = hits.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<Long> window = iterator.next().getValue();
            boolean stale;
            synchronized (window) {
                stale = window.isEmpty() || window.peekLast() <= cutoff;
            }
            if (stale) {
                iterator.remove();
            }
        }
    }
}
