package com.abavava.chat.observability;

import com.abavava.chat.contract.RealtimeStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Мгновенные метрики realtime-слоя: сколько пользователей и соединений сейчас
 * онлайн. Значения читаются из {@link RealtimeStats} (реализация — realtime.Hub)
 * при каждом скрейпе, поэтому Gauge не накапливает состояние и не требует
 * инкрементов в коде подключения.
 *
 * <p>Отдельно от {@link BusinessMetrics}: здесь нужен бин realtime-слоя, а
 * счётчики бизнес-событий должны быть доступны и без него (unit-тесты домена).
 */
@Component
public class RealtimeMetrics {

    public RealtimeMetrics(MeterRegistry registry, RealtimeStats realtime) {
        Gauge.builder(BusinessMetrics.PREFIX + "ws.connections", realtime, RealtimeStats::connections)
                .description("Активные WebSocket-соединения (мультидевайс считается поштучно).")
                .register(registry);
        Gauge.builder(BusinessMetrics.PREFIX + "ws.online_users", realtime, RealtimeStats::onlineUsers)
                .description("Пользователи хотя бы с одним активным WebSocket-соединением.")
                .register(registry);
    }
}
