package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.Call;
import com.abavava.chat.domain.CallKind;
import com.abavava.chat.domain.CallStatus;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Общие фикстуры для тестов телеметрии звонка: конфигурация из боевого
 * application.yml (с точечными override'ами), звонок и sink, собирающий NDJSON.
 */
final class TelemetryFixtures {

    static final UUID CALL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CALLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID CALLEE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID STRANGER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private TelemetryFixtures() {
    }

    /** Sink, который складывает строки в список — проще проверять NDJSON. */
    static final class RecordingSink implements TelemetrySink {

        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String ndjsonLine) {
            lines.add(ndjsonLine);
        }

        List<String> lines() {
            return lines;
        }
    }

    /**
     * Подменяет только чтение звонка: телеметрии больше ничего от репозитория не
     * нужно. Настоящий CallRepository не требует Mockito/БД для этого сценария
     * (конструктор лишь сохраняет ссылки).
     */
    static final class FakeCallRepository extends CallRepository {

        private final Map<UUID, Call> calls = new LinkedHashMap<>();
        private RuntimeException failure;

        FakeCallRepository() {
            super(null, null);
        }

        FakeCallRepository withCall(Call call) {
            calls.put(call.id(), call);
            return this;
        }

        FakeCallRepository failingWith(RuntimeException error) {
            this.failure = error;
            return this;
        }

        @Override
        public Call byId(UUID id) {
            if (failure != null) {
                throw failure;
            }
            Call call = calls.get(id);
            if (call == null) {
                throw com.abavava.chat.domain.AbvException.notFound("call not found");
            }
            return call;
        }
    }

    /** Звонок в статусе accepted: caller звонит callee. */
    static Call activeCall() {
        return new Call(
                CALL_ID,
                CONVERSATION_ID,
                CALLER_ID,
                CALLEE_ID,
                CallKind.AUDIO,
                CallStatus.ACCEPTED,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:00:05Z"),
                null,
                null,
                null,
                12,
                null);
    }

    /** Конфигурация из classpath application.yml + точечные переопределения. */
    static AbvProperties loadProps(String... overrides) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        ConfigurationPropertySource base = ConfigurationPropertySources.from(sources).iterator().next();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < overrides.length; i += 2) {
            values.put(overrides[i], overrides[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(values), base)
                .bind("abv", Bindable.of(AbvProperties.class))
                .orElseThrow(() -> new AssertionError("abv section is missing in application.yml"));
    }

    /** Валидное событие схемы v1 — тесты меняют только нужные поля. */
    static Map<String, Object> baseEvent(String eventName) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", CallTelemetryService.SCHEMA_VERSION);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("event", eventName);
        event.put("timestamp", "2026-01-01T10:00:06.000Z");
        event.put("callId", CALL_ID.toString());
        event.put("sessionId", "session-1");
        event.put("restartAttempt", 0);
        event.put("restartAttemptId", null);
        event.put("peerRole", "caller");
        event.put("browserName", "Chrome");
        event.put("browserVersion", "140.0.0.0");
        event.put("clientPlatform", "Win32");
        event.put("networkType", "4g");
        event.put("selectedCandidatePairId", "pair-1");
        event.put("statsIntervalMs", 2_000);
        event.put("details", new LinkedHashMap<String, Object>());
        return event;
    }
}
