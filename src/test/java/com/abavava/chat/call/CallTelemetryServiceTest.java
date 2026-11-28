package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.observability.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.abavava.chat.call.TelemetryFixtures.CALLEE_ID;
import static com.abavava.chat.call.TelemetryFixtures.CALL_ID;
import static com.abavava.chat.call.TelemetryFixtures.CALLER_ID;
import static com.abavava.chat.call.TelemetryFixtures.STRANGER_ID;
import static com.abavava.chat.call.TelemetryFixtures.activeCall;
import static com.abavava.chat.call.TelemetryFixtures.baseEvent;
import static com.abavava.chat.call.TelemetryFixtures.loadProps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Приём телеметрии: валидация, обогащение, дедупликация, санитайзинг details
 * и запись NDJSON. Каждое событие проверяется независимо — одно плохое не
 * отменяет весь batch.
 */
class CallTelemetryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelemetryFixtures.RecordingSink sink = new TelemetryFixtures.RecordingSink();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TelemetryFixtures.FakeCallRepository repository =
            new TelemetryFixtures.FakeCallRepository().withCall(activeCall());

    private CallTelemetryService service(AbvProperties props) {
        return new CallTelemetryService(props, repository, mapper, sink, new BusinessMetrics(registry));
    }

    @Test
    void countsAcceptedDuplicateAndDroppedTelemetryEvents() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> connected = baseEvent("ice_state_changed");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connection_state", "connected");
        details.put("ice_connection_state", "connected");
        connected.put("details", details);
        Map<String, Object> duplicate = baseEvent("ice_state_changed");
        duplicate.put("eventId", connected.get("eventId"));
        duplicate.put("details", details);
        Map<String, Object> broken = baseEvent("call_started");
        broken.put("schemaVersion", 2);

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(connected, duplicate, broken), "req-1");

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.dropped()).isEqualTo(1);
        assertThat(counter("ababawa.call_telemetry.events",
                "event", "ice_state_changed", "result", "accepted")).isEqualTo(1.0);
        assertThat(counter("ababawa.call_telemetry.events",
                "event", "ice_state_changed", "result", "duplicate")).isEqualTo(1.0);
        assertThat(counter("ababawa.call_telemetry.events",
                "event", "call_started", "result", "dropped")).isEqualTo(1.0);
        // Медиасоединение подтверждено клиентом (роль считает сервер).
        assertThat(counter("ababawa.calls.media_connected", "role", "callee")).isEqualTo(1.0);
    }

    @Test
    void mapsEventNamesOutsideTheSchemaToOtherLabel() throws Exception {
        CallTelemetryService service = service(loadProps());

        service.ingest(CALLEE_ID, List.of(baseEvent("definitely_not_an_event")), "req-1");

        assertThat(counter("ababawa.call_telemetry.events",
                "event", "other", "result", "dropped")).isEqualTo(1.0);
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Map<String, Object> onlyLine() {
        assertThat(sink.lines()).hasSize(1);
        String line = sink.lines().get(0);
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(line, Map.class);
        return parsed;
    }

    @Test
    void writesNdjsonLineWithSchemaAndServerEnrichment() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> event = baseEvent("session_started");
        event.put("restartAttempt", 2);
        event.put("restartAttemptId", "attempt-42");
        event.put("selectedCandidatePairId", "pair-7");
        event.put("statsIntervalMs", 500);
        details(event).put("kind", "audio");
        details(event).put("polite", false);

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(event), "req-1");

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.dropped()).isZero();
        assertThat(result.duplicates()).isZero();

        Map<String, Object> line = onlyLine();
        assertThat(line)
                .containsEntry("schemaVersion", 1)
                .containsEntry("event", "session_started")
                .containsEntry("callId", CALL_ID.toString())
                .containsEntry("sessionId", "session-1")
                .containsEntry("restartAttempt", 2)
                .containsEntry("restartAttemptId", "attempt-42")
                .containsEntry("selectedCandidatePairId", "pair-7")
                .containsEntry("statsIntervalMs", 500)
                .containsEntry("browserName", "Chrome")
                .containsEntry("browserVersion", "140.0.0.0")
                .containsEntry("clientPlatform", "Win32")
                .containsEntry("networkType", "4g")
                .containsEntry("userId", CALLEE_ID.toString())
                .containsEntry("requestId", "req-1")
                .containsEntry("callStatus", "accepted")
                .containsEntry("callKind", "audio")
                .containsEntry("durationSeconds", 12);
        assertThat(line.get("timestamp")).isEqualTo("2026-01-01T10:00:06Z");
        assertThat(line.get("serverReceivedAt")).isNotNull();
        // Роль определяет сервер: клиент прислал caller, но участник — callee.
        assertThat(line).containsEntry("peerRole", "callee");
        assertThat(line.get("details")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) line.get("details");
        assertThat(details).containsEntry("kind", "audio").containsEntry("polite", false);
        assertThat(line.get("eventId")).isEqualTo(event.get("eventId"));
    }

    @Test
    void rejectsDetailsThatIsNotAJsonObject() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> event = baseEvent("stats_sample");
        event.put("details", "{\"rtt_ms\":10}");

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(event), "req-1");

        assertThat(result.accepted()).isZero();
        assertThat(result.dropped()).isEqualTo(1);
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void rejectsUnknownEventAndUnsupportedSchemaVersion() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> unknown = baseEvent("definitely_not_an_event");
        Map<String, Object> future = baseEvent("call_started");
        future.put("schemaVersion", 2);

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(unknown, future), "req-1");

        assertThat(result.accepted()).isZero();
        assertThat(result.dropped()).isEqualTo(2);
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void stripsSecretsSdpCandidatesAndIpAddressesFromDetails() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> event = baseEvent("stats_sample");
        Map<String, Object> details = details(event);
        details.put("sdp", "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n");
        details.put("ice_candidate", "candidate:1 1 UDP 2122260223 192.168.1.7 5000 typ host");
        details.put("turn_credential", "alice:s3cret");
        details.put("local_ip", "192.168.1.7");
        details.put("note", "peer seen at 10.1.2.3");
        details.put("turn_url", "turn:turn.example.com:3478");
        details.put("candidate_pair_id", "pair-1");
        details.put("selected_candidate_pair_id", "pair-1");
        details.put("jitter_ms", 12);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("credential", "secret");
        nested.put("ok", true);
        details.put("nested", nested);

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(event), "req-1");

        assertThat(result.accepted()).isEqualTo(1);
        Map<String, Object> line = onlyLine();
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) line.get("details");
        assertThat(sanitized).doesNotContainKeys(
                "sdp", "ice_candidate", "turn_credential", "local_ip");
        assertThat(sanitized)
                .containsEntry("note", "peer seen at <redacted-ip>")
                .containsEntry("turn_url", "<redacted-ice-server>")
                .containsEntry("jitter_ms", 12)
                .containsEntry("candidate_pair_id", "pair-1")
                .containsEntry("selected_candidate_pair_id", "pair-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedSanitized = (Map<String, Object>) sanitized.get("nested");
        assertThat(nestedSanitized).containsEntry("ok", true).doesNotContainKey("credential");
        assertThat(line).containsEntry("detailsRedacted", true);
    }

    @Test
    void deduplicatesRetriedEventId() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> event = baseEvent("call_ended");
        String eventId = String.valueOf(event.get("eventId"));

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(event, event), "req-1");

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.dropped()).isZero();
        Map<String, Object> line = onlyLine();
        assertThat(line.get("eventId")).isEqualTo(eventId);
    }

    @Test
    void rejectsTooLargeBatch() throws Exception {
        CallTelemetryService service = service(loadProps("abv.telemetry.max-batch-events", "2"));
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 3; i += 1) {
            events.add(baseEvent("stats_sample"));
        }

        assertThatThrownBy(() -> service.ingest(CALLEE_ID, events, "req-1"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "events");
                });
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void dropsEventThatExceedsMaxNdjsonLineSize() throws Exception {
        CallTelemetryService service = service(loadProps(
                "abv.telemetry.max-event-bytes", "512"));
        Map<String, Object> event = baseEvent("stats_sample");
        details(event).put("note", "x".repeat(400));

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(event), "req-1");

        assertThat(result.accepted()).isZero();
        assertThat(result.dropped()).isEqualTo(1);
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void dropsUnknownCallAndNonParticipantButKeepsRequestSuccessful() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> unknownCall = baseEvent("stats_sample");
        unknownCall.put("callId", UUID.randomUUID().toString());
        Map<String, Object> stranger = baseEvent("stats_sample");

        CallTelemetryService.IngestResult result =
                service.ingest(STRANGER_ID, List.of(unknownCall, stranger), "req-1");

        assertThat(result.accepted()).isZero();
        assertThat(result.dropped()).isEqualTo(2);
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void databaseFailureDoesNotFailTheBatch() throws Exception {
        TelemetryFixtures.FakeCallRepository failing = new TelemetryFixtures.FakeCallRepository()
                .failingWith(new IllegalStateException("db is down"));
        CallTelemetryService service = new CallTelemetryService(
                loadProps(), failing, mapper, sink, new BusinessMetrics(registry));

        CallTelemetryService.IngestResult result =
                service.ingest(CALLEE_ID, List.of(baseEvent("stats_sample")), "req-1");

        assertThat(result.accepted()).isZero();
        assertThat(result.dropped()).isEqualTo(1);
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void validatesNumericFieldsAndAcceptsMissingStatsInterval() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> negativeInterval = baseEvent("stats_sample");
        negativeInterval.put("statsIntervalMs", -5);
        Map<String, Object> stringAttempt = baseEvent("call_started");
        stringAttempt.put("restartAttempt", "1");
        Map<String, Object> noInterval = baseEvent("call_started");
        noInterval.remove("statsIntervalMs");
        noInterval.remove("restartAttemptId");

        CallTelemetryService.IngestResult result = service.ingest(
                CALLER_ID, List.of(negativeInterval, stringAttempt, noInterval), "req-1");

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.dropped()).isEqualTo(2);
        Map<String, Object> line = onlyLine();
        assertThat(line.get("event")).isEqualTo("call_started");
        assertThat(line).containsEntry("peerRole", "caller");
        assertThat(line.get("statsIntervalMs")).isNull();
        assertThat(line.get("restartAttemptId")).isNull();
    }

    @Test
    void replacesInvalidClientTimestampWithServerTime() throws Exception {
        CallTelemetryService service = service(loadProps());
        Map<String, Object> event = baseEvent("call_started");
        event.put("timestamp", "not-a-timestamp");

        CallTelemetryService.IngestResult result =
                service.ingest(CALLER_ID, List.of(event), "req-1");

        assertThat(result.accepted()).isEqualTo(1);
        Map<String, Object> line = onlyLine();
        assertThat(line.get("timestamp")).isNotNull();
        assertThat(line.get("timestamp")).isNotEqualTo("not-a-timestamp");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> event) {
        return (Map<String, Object>) event.get("details");
    }
}
