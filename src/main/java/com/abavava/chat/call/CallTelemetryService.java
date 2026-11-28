package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.Call;
import com.abavava.chat.observability.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Приём клиентской телеметрии звонка: валидация → обогащение → NDJSON в stdout.
 *
 * <p>Правила (docs/API.md, «Телеметрия звонка»):
 * <ul>
 *   <li>каждое событие проверяется отдельно; невалидное отбрасывается и
 *       считается в {@code dropped}, остальные всё равно принимаются;</li>
 *   <li>участие в звонке и роль {@code peerRole} подтверждаются сервером, а не
 *       клиентом: клиентское значение не используется;</li>
 *   <li>{@code details} обязан быть JSON-объектом; строки, массивы и прочее —
 *       невалидное событие;</li>
 *   <li>из {@code details} вырезаются секреты/креды/TURN/SDP/IP-адреса
 *       (защита от случайной утечки), размеры ограничены;</li>
 *   <li>повтор {@code eventId} (retry клиента после сетевой ошибки) не пишется
 *       второй раз и учитывается в {@code duplicates};</li>
 *   <li>ошибка БД при обогащении не роняет запрос: телеметрия не должна мешать
 *       звонкам.</li>
 * </ul>
 */
@Service
public class CallTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(CallTelemetryService.class);

    /** Версия схемы событий; несовпадение — невалидное событие. */
    public static final int SCHEMA_VERSION = 1;

    /** Имена, которые принимает backend (совпадают с CallTelemetryEventName на клиенте). */
    static final Set<String> EVENT_NAMES = Set.of(
            "call_started",
            "call_ended",
            "session_started",
            "session_ended",
            "ice_state_changed",
            "ice_restart_requested",
            "ice_restart_attempt",
            "ice_restart_succeeded",
            "ice_restart_failed",
            "ice_restart_exhausted",
            "offer_collision",
            "candidate_ignored",
            "candidate_pair_changed",
            "stats_sample",
            "stats_degraded",
            "stats_recovered",
            "telemetry_dropped");

    /** Сколько последних eventId помним для дедупликации retry. */
    private static final int MAX_TRACKED_EVENT_IDS = 20_000;

    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_DESCRIPTOR_LENGTH = 64;
    private static final int MAX_STRING_LENGTH = 512;
    private static final int MAX_DETAILS_DEPTH = 4;
    private static final int MAX_DETAILS_KEYS_PER_LEVEL = 64;
    private static final int MAX_DETAILS_NODES = 200;
    private static final int MAX_LIST_ITEMS = 50;
    private static final int MAX_RESTART_ATTEMPT = 10_000;
    private static final int MAX_STATS_INTERVAL_MS = 3_600_000;

    /**
     * Ключи details, которые не пишем никогда: SDP/ICE, креды и адреса.
     * Проверка идёт по нормализованному ключу (нижний регистр, без разделителей)
     * и по суффиксу, чтобы поймать local_ip, turn_credential, remote_ufrag и т.п.
     * При этом candidate_pair_id / selectedCandidatePairId остаются: они не
     * содержат адресов и нужны для диагностики.
     */
    private static final Set<String> SENSITIVE_KEY_SUFFIXES = Set.of(
            "sdp",
            "candidate",
            "credential",
            "password",
            "secret",
            "token",
            "username",
            "ufrag",
            "icepwd",
            "ip",
            "ipaddress",
            "address",
            "host",
            "port");

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    private final AbvProperties properties;
    private final CallRepository calls;
    private final ObjectMapper mapper;
    private final TelemetrySink sink;
    private final BusinessMetrics metrics;

    /** LRU по eventId: защита от дублей при retry, ограниченная по памяти. */
    private final Map<String, Boolean> seenEventIds = new LinkedHashMap<>(1_024, 0.75f, true);

    public CallTelemetryService(AbvProperties properties,
                                CallRepository calls,
                                ObjectMapper mapper,
                                TelemetrySink sink,
                                BusinessMetrics metrics) {
        this.properties = properties;
        this.calls = calls;
        this.mapper = mapper;
        this.sink = sink;
        this.metrics = metrics;
    }

    /** Итог приёма batch'а: сколько записано, отброшено и признано дублями. */
    public record IngestResult(int accepted, int dropped, int duplicates) {
    }

    public IngestResult ingest(UUID userId, List<Map<String, Object>> rawEvents, String requestId) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            throw AbvException.invalidField("events", "events must not be empty");
        }
        int maxBatch = properties.telemetry().maxBatchEvents();
        if (rawEvents.size() > maxBatch) {
            throw AbvException.invalidField("events",
                    "events batch is too large (max " + maxBatch + ")");
        }

        int accepted = 0;
        int dropped = 0;
        int duplicates = 0;
        String lastReason = null;
        Map<UUID, Call> callsById = new HashMap<>();

        for (Map<String, Object> raw : rawEvents) {
            try {
                Map<String, Object> event = normalizeEvent(userId, raw, requestId, callsById);
                String eventId = String.valueOf(event.get("eventId"));
                String eventName = String.valueOf(event.get("event"));
                if (isDuplicate(eventId)) {
                    duplicates += 1;
                    metrics.telemetryEvent(eventName, "duplicate");
                    continue;
                }
                String line = mapper.writeValueAsString(event);
                if (line.getBytes(StandardCharsets.UTF_8).length > properties.telemetry().maxEventBytes()) {
                    throw new InvalidEvent("event_too_large");
                }
                sink.write(line);
                markSeen(eventId);
                accepted += 1;
                metrics.telemetryEvent(eventName, "accepted");
                countMediaConnected(event, eventName);
            } catch (InvalidEvent e) {
                dropped += 1;
                lastReason = e.reason();
                metrics.telemetryEvent(rawEventName(raw), "dropped");
                log.debug("call telemetry event dropped: reason={} event={}",
                        e.reason(), raw == null ? null : raw.get("event"));
            } catch (RuntimeException e) {
                dropped += 1;
                lastReason = "internal_error";
                metrics.telemetryEvent(rawEventName(raw), "dropped");
                log.debug("call telemetry event failed: {}", e.toString());
            }
        }

        if (dropped > 0) {
            // Одна сводка на batch вместо строки на каждое отброшенное событие.
            log.info("call telemetry batch: accepted={} dropped={} duplicates={} last_reason={} user_id={} request_id={}",
                    accepted, dropped, duplicates, lastReason, userId, requestId);
        }
        return new IngestResult(accepted, dropped, duplicates);
    }

    /* ------------------------------------------------------------------ */
    /* Валидация и обогащение                                              */
    /* ------------------------------------------------------------------ */

    /** Имя события из «сырого» элемента batch'а (может быть мусором). */
    private static String rawEventName(Map<String, Object> raw) {
        Object value = raw == null ? null : raw.get("event");
        return value instanceof String text ? text : null;
    }

    /**
     * Признак установленного медиасоединения: клиент сообщил о переходе
     * RTCPeerConnection в {@code connected}. Сервер это подтвердить не может
     * (медиа идёт мимо), поэтому метрика клиентская и считается по переходам
     * состояния, а не по звонкам: после ICE restart их бывает несколько.
     */
    private void countMediaConnected(Map<String, Object> event, String eventName) {
        if (!"ice_state_changed".equals(eventName)) {
            return;
        }
        Object details = event.get("details");
        if (!(details instanceof Map<?, ?> map) || !"connected".equals(map.get("connection_state"))) {
            return;
        }
        Object role = event.get("peerRole");
        metrics.callMediaConnected(role instanceof String text ? text : null);
    }

    private Map<String, Object> normalizeEvent(UUID userId,
                                               Map<String, Object> raw,
                                               String requestId,
                                               Map<UUID, Call> callsById) {
        if (raw == null) {
            throw new InvalidEvent("event_not_an_object");
        }

        int schemaVersion = requireSchemaVersion(raw);
        String eventName = requireString(raw, "event", MAX_ID_LENGTH);
        if (!EVENT_NAMES.contains(eventName)) {
            throw new InvalidEvent("unknown_event");
        }
        String eventId = optionalIdentifier(raw, "eventId", MAX_ID_LENGTH);
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        String callIdRaw = requireString(raw, "callId", MAX_ID_LENGTH);
        UUID callId = parseUuid(callIdRaw);
        String sessionId = requireString(raw, "sessionId", MAX_ID_LENGTH);
        Instant timestamp = parseTimestamp(raw.get("timestamp"), eventId);

        int restartAttempt = optionalInt(raw, "restartAttempt", 0, 0, MAX_RESTART_ATTEMPT);
        String restartAttemptId = optionalIdentifier(raw, "restartAttemptId", MAX_ID_LENGTH);
        Integer statsIntervalMs = optionalNullableInt(
                raw, "statsIntervalMs", 0, MAX_STATS_INTERVAL_MS);

        DetailsSanitizer details = new DetailsSanitizer();
        Map<String, Object> sanitizedDetails = details.sanitize(raw.get("details"));

        Call call = resolveCall(callId, callsById);
        if (!call.involved(userId)) {
            throw new InvalidEvent("not_a_participant");
        }
        String peerRole = call.callerId().equals(userId) ? "caller" : "callee";

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", schemaVersion);
        event.put("eventId", eventId);
        event.put("event", eventName);
        event.put("timestamp", timestamp.toString());
        event.put("callId", callId.toString());
        event.put("sessionId", sessionId);
        event.put("restartAttempt", restartAttempt);
        event.put("restartAttemptId", restartAttemptId);
        event.put("peerRole", peerRole);
        event.put("browserName", optionalDescriptor(raw, "browserName", MAX_DESCRIPTOR_LENGTH));
        event.put("browserVersion", optionalDescriptor(raw, "browserVersion", MAX_DESCRIPTOR_LENGTH));
        event.put("clientPlatform", optionalDescriptor(raw, "clientPlatform", MAX_DESCRIPTOR_LENGTH));
        event.put("networkType", optionalDescriptor(raw, "networkType", MAX_DESCRIPTOR_LENGTH));
        event.put("selectedCandidatePairId",
                optionalIdentifier(raw, "selectedCandidatePairId", MAX_ID_LENGTH));
        event.put("statsIntervalMs", statsIntervalMs);
        event.put("details", sanitizedDetails);

        // Обогащение сервером: клиенту эти значения недостоверны/недоступны.
        event.put("serverReceivedAt", nowMillis().toString());
        event.put("userId", userId.toString());
        event.put("requestId", requestId);
        event.put("callStatus", call.status().json());
        event.put("callKind", call.kind().json());
        if (call.endReason() != null) {
            event.put("endReason", call.endReason());
        }
        event.put("durationSeconds", call.durationSeconds());
        if (details.truncated) {
            event.put("detailsTruncated", true);
        }
        if (details.redacted) {
            event.put("detailsRedacted", true);
        }
        return event;
    }

    private static int requireSchemaVersion(Map<String, Object> raw) {
        Object value = raw.containsKey("schemaVersion") ? raw.get("schemaVersion") : raw.get("schema_version");
        if (value == null) {
            throw new InvalidEvent("schema_version_missing");
        }
        if (!(value instanceof Number number)) {
            throw new InvalidEvent("schema_version_not_a_number");
        }
        double asDouble = number.doubleValue();
        if (asDouble != SCHEMA_VERSION) {
            throw new InvalidEvent("schema_version_unsupported");
        }
        return SCHEMA_VERSION;
    }

    private Call resolveCall(UUID callId, Map<UUID, Call> callsById) {
        Call cached = callsById.get(callId);
        if (cached != null) {
            return cached;
        }
        Call call;
        try {
            call = calls.byId(callId);
        } catch (AbvException e) {
            throw new InvalidEvent(e.code() == AbvException.Code.NOT_FOUND
                    ? "call_not_found"
                    : "call_lookup_failed");
        } catch (RuntimeException e) {
            // БД недоступна: телеметрия не должна отвечать 500 и провоцировать
            // retry-шторм на клиенте.
            throw new InvalidEvent("call_lookup_failed");
        }
        callsById.put(callId, call);
        return call;
    }

    private static String requireString(Map<String, Object> raw, String key, int maxLength) {
        Object value = raw.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidEvent(key + "_missing");
        }
        String trimmed = text.strip();
        if (trimmed.length() > maxLength) {
            throw new InvalidEvent(key + "_too_long");
        }
        return trimmed;
    }

    private static String optionalIdentifier(Map<String, Object> raw, String key, int maxLength) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new InvalidEvent(key + "_not_a_string");
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new InvalidEvent(key + "_too_long");
        }
        return trimmed;
    }

    /** Свободный текст (браузер, платформа, сеть): не строка — null, не ошибка. */
    private static String optionalDescriptor(Map<String, Object> raw, String key, int maxLength) {
        Object value = raw.get(key);
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private static int optionalInt(Map<String, Object> raw, String key, int fallback, int min, int max) {
        Integer value = optionalNullableInt(raw, key, min, max);
        return value == null ? fallback : value;
    }

    private static Integer optionalNullableInt(Map<String, Object> raw, String key, int min, int max) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidEvent(key + "_not_a_number");
        }
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)
                || asDouble < min || asDouble > max) {
            throw new InvalidEvent(key + "_out_of_range");
        }
        return (int) asDouble;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException e) {
            throw new InvalidEvent("call_id_invalid");
        }
    }

    /** Клиентский timestamp: невалидный не теряет событие — подставляем время сервера. */
    private static Instant parseTimestamp(Object raw, String eventId) {
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // ниже — фолбэк
            }
        }
        log.debug("call telemetry timestamp replaced by server time: event_id={}", eventId);
        return nowMillis();
    }

    /** Время сервера с точностью до миллисекунд: короче строки и стабильный формат. */
    private static Instant nowMillis() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /* ------------------------------------------------------------------ */
    /* Дедупликация                                                        */
    /* ------------------------------------------------------------------ */

    private boolean isDuplicate(String eventId) {
        synchronized (seenEventIds) {
            return seenEventIds.containsKey(eventId);
        }
    }

    private void markSeen(String eventId) {
        synchronized (seenEventIds) {
            seenEventIds.put(eventId, Boolean.TRUE);
            while (seenEventIds.size() > MAX_TRACKED_EVENT_IDS) {
                var iterator = seenEventIds.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Санитайзер details                                                  */
    /* ------------------------------------------------------------------ */

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isEmpty()) {
            return true;
        }
        for (String suffix : SENSITIVE_KEY_SUFFIXES) {
            if (normalized.equals(suffix) || normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Рекурсивно копирует details, вырезая чувствительные ключи и значения.
     * Ограничения глубины/размера не дают клиенту раздуть лог.
     */
    private static final class DetailsSanitizer {

        private boolean truncated;
        private boolean redacted;
        private int nodes;

        Map<String, Object> sanitize(Object rawDetails) {
            if (rawDetails == null) {
                return new LinkedHashMap<>();
            }
            if (!(rawDetails instanceof Map<?, ?> map)) {
                // details строкой/массивом — невалидное событие (контракт v1).
                throw new InvalidEvent("details_not_an_object");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            copyMap(map, out, 0);
            return out;
        }

        private void copyMap(Map<?, ?> source, Map<String, Object> target, int depth) {
            int added = 0;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (added >= MAX_DETAILS_KEYS_PER_LEVEL || nodes >= MAX_DETAILS_NODES) {
                    truncated = true;
                    return;
                }
                Object rawKey = entry.getKey();
                if (rawKey == null) {
                    truncated = true;
                    continue;
                }
                String key = String.valueOf(rawKey);
                if (isSensitiveKey(key)) {
                    redacted = true;
                    continue;
                }
                Object value = copyValue(entry.getValue(), depth + 1);
                if (value == null && entry.getValue() != null) {
                    // Значение неизвестного типа (byte[], объект и т.п.) не пишем.
                    truncated = true;
                    continue;
                }
                target.put(key, value);
                added += 1;
            }
        }

        private Object copyValue(Object value, int depth) {
            if (value == null) {
                return null;
            }
            nodes += 1;
            if (nodes > MAX_DETAILS_NODES) {
                truncated = true;
                return null;
            }
            if (value instanceof Boolean) {
                return value;
            }
            if (value instanceof Number number) {
                return Double.isFinite(number.doubleValue()) ? value : null;
            }
            if (value instanceof CharSequence text) {
                return redactText(text.toString());
            }
            if (value instanceof Map<?, ?> map) {
                if (depth >= MAX_DETAILS_DEPTH) {
                    truncated = true;
                    return null;
                }
                Map<String, Object> nested = new LinkedHashMap<>();
                copyMap(map, nested, depth);
                return nested;
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> list = new ArrayList<>();
                for (Object item : iterable) {
                    if (list.size() >= MAX_LIST_ITEMS) {
                        truncated = true;
                        break;
                    }
                    Object copied = copyValue(item, depth + 1);
                    if (copied != null) {
                        list.add(copied);
                    }
                }
                return list;
            }
            return null;
        }

        private String redactText(String value) {
            boolean cut = value.length() > MAX_STRING_LENGTH;
            String text = cut ? value.substring(0, MAX_STRING_LENGTH) : value;
            String lower = text.strip().toLowerCase(Locale.ROOT);

            String replacement = null;
            if (lower.startsWith("v=0") || lower.contains("a=ice-ufrag:")
                    || lower.contains("a=ice-pwd:") || lower.contains("a=candidate:")) {
                replacement = "<redacted-sdp>";
            } else if (lower.startsWith("candidate:") || lower.contains(" typ host")
                    || lower.contains(" typ srflx") || lower.contains(" typ relay")) {
                replacement = "<redacted-candidate>";
            } else if (lower.startsWith("turn:") || lower.startsWith("turns:")
                    || lower.startsWith("stun:") || lower.startsWith("stuns:")) {
                replacement = "<redacted-ice-server>";
            } else if (IPV4.matcher(text).find()) {
                replacement = IPV4.matcher(text).replaceAll("<redacted-ip>");
            }

            if (replacement != null) {
                redacted = true;
                return replacement;
            }
            return cut ? text + "…" : text;
        }
    }

    /** Внутренняя ошибка валидации одного события: batch продолжается. */
    private static final class InvalidEvent extends RuntimeException {

        private final String reason;

        InvalidEvent(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
