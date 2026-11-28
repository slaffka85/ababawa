package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.http.CurrentUser;
import com.abavava.chat.http.Httpx;
import com.abavava.chat.http.JsonBody;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/v1/calls/telemetry — приём batch'а клиентской телеметрии звонка.
 *
 * <p>Ответ всегда {@code 202 Accepted}: событие, которое не прошло валидацию,
 * не должно заставлять клиента повторять весь batch. Сколько записано,
 * отброшено и признано дублями — в теле ответа. Ошибки формата запроса (тело не
 * JSON, пустой или слишком большой batch) — обычные 400/422 из контракта.
 */
@RestController
@RequestMapping("/api/v1")
public class CallTelemetryController {

    /** Тело: {"events": [{...}, ...]} — ровно один batch за запрос. */
    public record BatchRequest(
            @JsonProperty("events") List<Map<String, Object>> events) {
    }

    /**
     * Результат приёма. {@code disabled} — приём выключен конфигурацией backend:
     * клиент должен прекратить отправку, не считая это ошибкой.
     */
    public record AcceptedResponse(
            @JsonProperty("accepted") int accepted,
            @JsonProperty("dropped") int dropped,
            @JsonProperty("duplicates") int duplicates,
            @JsonProperty("disabled") boolean disabled) {
    }

    private final CallTelemetryService telemetry;
    private final ObjectMapper mapper;
    private final AbvProperties properties;

    public CallTelemetryController(CallTelemetryService telemetry,
                                   ObjectMapper mapper,
                                   AbvProperties properties) {
        this.telemetry = telemetry;
        this.mapper = mapper;
        this.properties = properties;
    }

    @PostMapping("/calls/telemetry")
    public ResponseEntity<AcceptedResponse> ingest(HttpServletRequest request) {
        UUID actor = CurrentUser.userId(request);
        BatchRequest body = JsonBody.required(request, BatchRequest.class, mapper);
        if (!properties.telemetry().enabled()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new AcceptedResponse(0, 0, 0, true));
        }
        CallTelemetryService.IngestResult result =
                telemetry.ingest(actor, body.events(), requestId(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AcceptedResponse(result.accepted(), result.dropped(),
                        result.duplicates(), false));
    }

    private static final int MAX_REQUEST_ID_LENGTH = 128;

    /** X-Request-ID приходит от клиента: пропускаем только безопасный алфавит. */
    private static final java.util.regex.Pattern SAFE_REQUEST_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_REQUEST_ID_LENGTH + "}");

    private static String requestId(HttpServletRequest request) {
        Object raw = request.getAttribute(Httpx.REQUEST_ID_ATTRIBUTE);
        if (raw instanceof String text && SAFE_REQUEST_ID.matcher(text).matches()) {
            return text;
        }
        // Некорректный/подозрительный id не тащим в NDJSON: он попал бы в логи
        // и позволил бы подделать строки.
        return UUID.randomUUID().toString();
    }
}
