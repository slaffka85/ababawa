package com.abavava.chat.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Бизнес-метрики и метрики подозрительной активности ababawa.
 *
 * <p>Единственная точка, где приложение создаёт счётчики: доменный код зовёт
 * методы вида {@code login("failure", "invalid_credentials")}, а не работает с
 * {@link MeterRegistry} напрямую. Имена метрик — с общим префиксом
 * {@code ababawa.} (в Prometheus он станет {@code ababawa_...}); суффикс
 * {@code _total} добавляет сам Prometheus-реэстр Micrometer.
 *
 * <p>Правила кардинальности (docs/METRICS.md):
 * <ul>
 *   <li>в метках нет IP, email, userId, callId, sessionId, URL и текстов
 *       исключений — только значения из фиксированных наборов ниже;</li>
 *   <li>любое значение вне набора превращается в {@code other}: опечатка или
 *       новое значение не создаёт новую временную серию;</li>
 *   <li>размер каждого набора ограничен константой, поэтому число серий каждой
 *       метрики предсказуемо (произведение размеров наборов).</li>
 * </ul>
 *
 * <p>Счётчики создаются при первом событии: метрика без событий отсутствует в
 * {@code /actuator/prometheus} (панели Grafana показывают «no data», а не 0).
 */
@Component
public class BusinessMetrics {

    /** Общий префикс: отличает метрики ababawa от других проектов в Prometheus. */
    public static final String PREFIX = "ababawa.";

    /** Значение метки {@code result} у успешного события. */
    public static final String SUCCESS = "success";
    /** Значение метки {@code result} у неуспешного события. */
    public static final String FAILURE = "failure";
    /** Значение метки {@code reason} у успешного события (причина не нужна). */
    public static final String NO_REASON = "none";
    /** Подстановка для значения метки вне разрешённого набора. */
    public static final String OTHER = "other";

    // ------------------------------------------------------------- наборы значений

    private static final Set<String> RESULTS = Set.of(SUCCESS, FAILURE);

    private static final Set<String> REGISTRATION_REASONS = Set.of(
            NO_REASON, "invalid_input", "email_taken", "internal_error");

    /** Зачем выпускается код подтверждения. */
    private static final Set<String> OTP_PURPOSES = Set.of(
            "registration", "resend", "manual", "password_reset");

    /** Чем закончился запрос на отправку кода (метка {@code reason}). */
    private static final Set<String> OTP_REQUEST_REASONS = Set.of(
            NO_REASON, "mail_not_queued", "rate_limited", "unknown_email",
            "not_registered", "already_verified", "disabled", "invalid_input", "internal_error");

    /** Вид письма: подтверждение адреса или сброс пароля. */
    private static final Set<String> MAIL_KINDS = Set.of("verification", "password_reset");

    /** Что именно проверяли кодом: подтверждение адреса или сброс пароля. */
    private static final Set<String> VERIFICATION_PURPOSES = Set.of("email_verify", "password_reset");

    private static final Set<String> VERIFICATION_REASONS = Set.of(
            NO_REASON, "invalid_code", "expired", "too_many_attempts",
            "already_verified", "invalid_input", "internal_error");

    private static final Set<String> LOGIN_REASONS = Set.of(
            NO_REASON, "invalid_credentials", "email_not_verified", "invalid_input", "internal_error");

    private static final Set<String> REFRESH_REASONS = Set.of(
            NO_REASON, "invalid_token", "token_reuse", "unknown_user", "internal_error");

    private static final Set<String> CALL_KINDS = Set.of("audio", "video", "unknown");

    private static final Set<String> CALL_START_REASONS = Set.of(
            NO_REASON, "invalid_input", "self_call", "callee_not_found", "busy", "internal_error");

    /** Итоговый статус звонка в момент его завершения. */
    private static final Set<String> CALL_STATUSES = Set.of(
            "ended", "rejected", "canceled", "missed", "failed");

    /** Тип кадра WebRTC-сигналинга, который пересылает сервер. */
    private static final Set<String> SIGNALING_EVENTS = Set.of(
            "offer", "answer", "candidate", "unknown");

    private static final Set<String> SIGNALING_REASONS = Set.of(
            NO_REASON, "invalid_payload", "not_participant", "call_finished",
            "peer_offline", "not_found", "internal_error");

    /** Роль участника в клиентской телеметрии (её определяет сервер). */
    private static final Set<String> ROLES = Set.of("caller", "callee");

    /** Что ограничивал rate limit (в ababawa окно общее для /auth/* и /mail/*). */
    private static final Set<String> RATE_LIMIT_SCOPES = Set.of("auth", "mail");

    /** Канал, на котором отказано в доступе к защищённому ресурсу. */
    private static final Set<String> CHANNELS = Set.of("http", "websocket");

    private static final Set<String> ACCESS_DENIED_REASONS = Set.of(
            "missing_token", "invalid_token", "user_not_found", "origin_denied");

    private static final Set<String> TELEMETRY_RESULTS = Set.of("accepted", "dropped", "duplicate");

    /** События клиентской телеметрии звонка (схема v1, см. docs/API.md). */
    private static final Set<String> TELEMETRY_EVENTS = Set.of(
            "call_started", "call_ended", "session_started", "session_ended",
            "ice_state_changed", "ice_restart_requested", "ice_restart_attempt",
            "ice_restart_succeeded", "ice_restart_failed", "ice_restart_exhausted",
            "offer_collision", "candidate_ignored", "candidate_pair_changed",
            "stats_sample", "stats_degraded", "stats_recovered", "telemetry_dropped");

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ---------------------------------------------------------------- регистрация

    /** Попытка регистрации: {@code result=success|failure}, причина отказа. */
    public void registration(String result, String reason) {
        counter("ababawa.auth.registration",
                "Попытки регистрации по email.",
                "result", label(result, RESULTS),
                "reason", label(reason, REGISTRATION_REASONS));
    }

    /**
     * Запрос на отправку кода подтверждения (регистрация, повторная отправка,
     * ручная ручка, сброс пароля). {@code result=success} — код выпущен и письмо
     * принято в очередь.
     */
    public void otpRequest(String purpose, String result, String reason) {
        counter("ababawa.auth.otp.requests",
                "Запросы на отправку одноразового кода.",
                "purpose", label(purpose, OTP_PURPOSES),
                "result", label(result, RESULTS),
                "reason", label(reason, OTP_REQUEST_REASONS));
    }

    /**
     * Результат постановки письма с кодом в очередь отправки.
     * {@code failure} — почта выключена, адрес битый или очередь переполнена;
     * ошибка самого SMTP видна в {@link #mailDelivery(String)}.
     */
    public void otpEmail(String kind, String result) {
        counter("ababawa.auth.otp.emails",
                "Письма с одноразовым кодом: поставлены в очередь или нет.",
                "kind", label(kind, MAIL_KINDS),
                "result", label(result, RESULTS));
    }

    /** Проверка одноразового кода: успешная или нет, с причиной отказа. */
    public void otpVerification(String purpose, String result, String reason) {
        counter("ababawa.auth.otp.verifications",
                "Проверки одноразового кода.",
                "purpose", label(purpose, VERIFICATION_PURPOSES),
                "result", label(result, RESULTS),
                "reason", label(reason, VERIFICATION_REASONS));
    }

    // --------------------------------------------------------------- авторизация

    /** Попытка входа по email и паролю. */
    public void login(String result, String reason) {
        counter("ababawa.auth.logins",
                "Попытки входа.",
                "result", label(result, RESULTS),
                "reason", label(reason, LOGIN_REASONS));
    }

    /** Ротация refresh-токена (в т.ч. обнаруженное повторное использование). */
    public void refresh(String result, String reason) {
        counter("ababawa.auth.refresh",
                "Ротации refresh-токена.",
                "result", label(result, RESULTS),
                "reason", label(reason, REFRESH_REASONS));
    }

    // --------------------------------------------------------------------- звонки

    /** Попытка создать звонок: успешная либо с причиной отказа. */
    public void callStart(String result, String reason) {
        counter("ababawa.calls.start",
                "Попытки создать звонок.",
                "result", label(result, RESULTS),
                "reason", label(reason, CALL_START_REASONS));
    }

    /** Успешно созданный звонок (запись в БД, статус ringing). */
    public void callCreated(String kind) {
        // Имя не "ababawa.calls.created": Prometheus-клиент считает суффикс
        // "_created" служебным (время создания счётчика) и вырезает его.
        counter("ababawa.calls.initiated",
                "Созданные звонки по типу.",
                "kind", label(kind, CALL_KINDS));
    }

    /**
     * Звонок принят вызываемым: signaling-уровень установлен. Медиа при этом
     * может не подняться — см. {@link #callMediaConnected(String)}.
     */
    public void callAccepted(String kind) {
        counter("ababawa.calls.accepted",
                "Принятые звонки (callee нажал «принять»).",
                "kind", label(kind, CALL_KINDS));
    }

    /** Звонок завершён с итоговым статусом. */
    public void callEnded(String status) {
        counter("ababawa.calls.ended",
                "Завершённые звонки по итоговому статусу.",
                "status", label(status, CALL_STATUSES));
    }

    /**
     * Пересылка кадра WebRTC-сигналинга второму участнику.
     * {@code result=success} — кадр передан в realtime-слой.
     */
    public void callSignaling(String event, String result, String reason) {
        counter("ababawa.calls.signaling",
                "Пересылка WebRTC-сигналинга (offer/answer/candidate).",
                "event", label(event, SIGNALING_EVENTS),
                "result", label(result, RESULTS),
                "reason", label(reason, SIGNALING_REASONS));
    }

    /**
     * Клиент сообщил о переходе RTCPeerConnection в состояние connected: это
     * единственный достоверный признак установленного медиасоединения.
     * Источник — клиентская телеметрия, поэтому метрика недоверенная (клиент
     * может её не прислать или прислать повторно после ICE restart).
     */
    public void callMediaConnected(String role) {
        counter("ababawa.calls.media_connected",
                "Переходы звонка в медиасоединение (по клиентской телеметрии).",
                "role", label(role, ROLES));
    }

    /** Длительность состоявшегося звонка (answered_at → ended_at), секунды. */
    public void callDuration(String kind, long seconds) {
        Timer.builder(PREFIX + "calls.duration")
                .description("Длительность завершённых звонков, секунды.")
                .tag("kind", label(kind, CALL_KINDS))
                .register(registry)
                .record(Math.max(0L, seconds), TimeUnit.SECONDS);
    }

    // ------------------------------------------------------- почта (SMTP-доставка)

    /**
     * Итог фоновой отправки письма по SMTP. {@code failure} — транспорт не
     * принял письмо (сеть, TLS, авторизация, отказ сервера); текст ошибки
     * остаётся в логе и в метку не попадает.
     */
    public void mailDelivery(String result) {
        counter("ababawa.mail.deliveries",
                "Фоновые попытки доставить письмо по SMTP.",
                "result", label(result, RESULTS));
    }

    // --------------------------------------------- подозрительная активность / доступ

    /** Запрос отклонён скользящим окном rate limit (abv.auth-rate-limit). */
    public void rateLimited(String scope) {
        counter("ababawa.security.rate_limited",
                "Запросы, отклонённые rate limit'ом.",
                "scope", label(scope, RATE_LIMIT_SCOPES));
    }

    /**
     * Отказ в доступе к защищённому ресурсу (HTTP-фильтр или рукопожатие
     * WebSocket). Причина — из закрытого набора: подделанный/просроченный токен,
     * неизвестный пользователь, запрещённый Origin.
     */
    public void accessDenied(String channel, String reason) {
        counter("ababawa.security.access_denied",
                "Отказы в доступе к защищённым ресурсам.",
                "channel", label(channel, CHANNELS),
                "reason", label(reason, ACCESS_DENIED_REASONS));
    }

    /** Событие клиентской телеметрии звонка: принято, отброшено или дубль. */
    public void telemetryEvent(String event, String result) {
        counter("ababawa.call_telemetry.events",
                "События клиентской телеметрии звонка.",
                "event", label(event, TELEMETRY_EVENTS),
                "result", label(result, TELEMETRY_RESULTS));
    }

    // ------------------------------------------------------------------ помощники

    /** Значение метки из разрешённого набора; всё остальное — {@code other}. */
    static String label(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : OTHER;
    }

    private void counter(String name, String description, String... tags) {
        Counter.builder(name)
                .description(description)
                .tags(tags)
                .register(registry)
                .increment();
    }
}
