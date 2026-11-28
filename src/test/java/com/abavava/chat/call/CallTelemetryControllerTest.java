package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.http.Httpx;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.abavava.chat.call.TelemetryFixtures.CALLEE_ID;
import static com.abavava.chat.call.TelemetryFixtures.activeCall;
import static com.abavava.chat.call.TelemetryFixtures.baseEvent;
import static com.abavava.chat.call.TelemetryFixtures.loadProps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** POST /api/v1/calls/telemetry: код ответа, тело и поведение при выключенном приёме. */
class CallTelemetryControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelemetryFixtures.RecordingSink sink = new TelemetryFixtures.RecordingSink();
    private final TelemetryFixtures.FakeCallRepository repository =
            new TelemetryFixtures.FakeCallRepository().withCall(activeCall());

    private CallTelemetryController controller(AbvProperties props) {
        CallTelemetryService service = new CallTelemetryService(props, repository, mapper, sink);
        return new CallTelemetryController(service, mapper, props);
    }

    private MockHttpServletRequest request(String body, String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setAttribute(Httpx.USER_ID_ATTRIBUTE, CALLEE_ID);
        request.setAttribute(Httpx.REQUEST_ID_ATTRIBUTE, requestId);
        return request;
    }

    private String batchBody(Map<String, Object>... events) throws Exception {
        return mapper.writeValueAsString(Map.of("events", List.of(events)));
    }

    @Test
    void acceptsBatchAndReturnsCounters() throws Exception {
        CallTelemetryController controller = controller(loadProps());
        String body = batchBody(baseEvent("session_started"), baseEvent("session_ended"));

        var response = controller.ingest(request(body, "req-9"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isEqualTo(2);
        assertThat(response.getBody().dropped()).isZero();
        assertThat(response.getBody().duplicates()).isZero();
        assertThat(response.getBody().disabled()).isFalse();
        assertThat(sink.lines()).hasSize(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = mapper.readValue(sink.lines().get(0), Map.class);
        assertThat(first).containsEntry("requestId", "req-9");
    }

    @Test
    void reportsInvalidEventsWithoutFailingTheBatch() throws Exception {
        CallTelemetryController controller = controller(loadProps());
        Map<String, Object> broken = baseEvent("session_started");
        broken.put("details", "not-an-object");
        String body = batchBody(broken, baseEvent("call_started"));

        var response = controller.ingest(request(body, "req-9"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().accepted()).isEqualTo(1);
        assertThat(response.getBody().dropped()).isEqualTo(1);
        assertThat(sink.lines()).hasSize(1);
    }

    @Test
    void disabledTelemetryAcceptsButWritesNothing() throws Exception {
        CallTelemetryController controller = controller(loadProps("abv.telemetry.enabled", "false"));
        String body = batchBody(baseEvent("call_started"));

        var response = controller.ingest(request(body, "req-9"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().disabled()).isTrue();
        assertThat(response.getBody().accepted()).isZero();
        assertThat(sink.lines()).isEmpty();
    }

    @Test
    void rejectsEmptyBodyAndMalformedJson() throws Exception {
        CallTelemetryController controller = controller(loadProps());

        assertThatThrownBy(() -> controller.ingest(request("", "req-9")))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.INVALID_REQUEST));

        assertThatThrownBy(() -> controller.ingest(request("{\"events\":[", "req-9")))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.INVALID_REQUEST));
    }

    @Test
    void rejectsEmptyAndOversizedBatches() throws Exception {
        CallTelemetryController controller = controller(loadProps("abv.telemetry.max-batch-events", "1"));

        assertThatThrownBy(() -> controller.ingest(request("{\"events\":[]}", "req-9")))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "events");
                });

        String body = batchBody(baseEvent("call_started"), baseEvent("session_started"));
        assertThatThrownBy(() -> controller.ingest(request(body, "req-9")))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.VALIDATION_FAILED));
    }
}
