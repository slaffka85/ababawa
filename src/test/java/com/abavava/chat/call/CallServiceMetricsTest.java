package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.contract.Broadcaster;
import com.abavava.chat.contract.ChatCommands;
import com.abavava.chat.contract.UserLookup;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.Call;
import com.abavava.chat.domain.CallKind;
import com.abavava.chat.domain.CallStatus;
import com.abavava.chat.domain.Events;
import com.abavava.chat.domain.User;
import com.abavava.chat.observability.BusinessMetrics;
import com.abavava.chat.push.FcmPushSender;
import com.abavava.chat.push.PushDeviceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Метрики жизненного цикла звонка на реальном {@link CallService}: создание,
 * отказы, принятие, завершение, длительность и ошибки сигналинга.
 *
 * <p>Проверяется бизнес-событие, а не вызов метода: {@code calls.created}
 * растёт только после записи звонка, {@code calls.accepted} — только после
 * успешного перехода ringing → accepted.
 */
class CallServiceMetricsTest {

    private static final UUID CALLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CALLEE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SimpleMeterRegistry registry;
    private FakeCallRepository repository;
    private FakeUsers users;
    private CallService service;

    @BeforeEach
    void setUp() throws Exception {
        AbvProperties props = loadProps();
        registry = new SimpleMeterRegistry();
        repository = new FakeCallRepository();
        users = new FakeUsers();
        service = new CallService(repository, users, new FakeChat(),
                provider(new RecordingBroadcaster()), new FakePushDevices(),
                new FcmPushSender("", new ObjectMapper()), props, new BusinessMetrics(registry));
    }

    @AfterEach
    void tearDown() {
        // Останавливаем поток таймеров дозвона, чтобы он не пережил тест.
        service.close();
    }

    @Test
    void countsCreatedAcceptedEndedCallAndItsDuration() {
        Call created = service.start(CALLER_ID,
                new CallService.StartInput(CALLEE_ID, "audio", CONVERSATION_ID));
        service.accept(CALLEE_ID, created.id());
        service.end(CALLER_ID, created.id(), "hangup");

        assertThat(counter("ababawa.calls.start", "result", "success", "reason", "none"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.calls.initiated", "kind", "audio")).isEqualTo(1.0);
        assertThat(counter("ababawa.calls.accepted", "kind", "audio")).isEqualTo(1.0);
        assertThat(counter("ababawa.calls.ended", "status", "ended")).isEqualTo(1.0);
        var timer = registry.get("ababawa.calls.duration").tag("kind", "audio").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(12.0);
    }

    @Test
    void countsBusyAsStartFailureAndDoesNotCountCreatedCall() {
        repository.busyForCallee = ringingCall();

        assertThatThrownBy(() -> service.start(CALLER_ID,
                new CallService.StartInput(CALLEE_ID, "video", CONVERSATION_ID)))
                .isInstanceOf(AbvException.class);

        assertThat(counter("ababawa.calls.start", "result", "failure", "reason", "busy"))
                .isEqualTo(1.0);
        assertThat(registry.find("ababawa.calls.initiated").counter()).isNull();
    }

    @Test
    void countsSelfCallAndUnknownCalleeAsStartFailures() {
        assertThatThrownBy(() -> service.start(CALLER_ID,
                new CallService.StartInput(CALLER_ID, "audio", CONVERSATION_ID)))
                .isInstanceOf(AbvException.class);

        users.unknown = CALLEE_ID;
        assertThatThrownBy(() -> service.start(CALLER_ID,
                new CallService.StartInput(CALLEE_ID, "audio", CONVERSATION_ID)))
                .isInstanceOf(AbvException.class);

        assertThat(counter("ababawa.calls.start", "result", "failure", "reason", "self_call"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.calls.start", "result", "failure", "reason", "callee_not_found"))
                .isEqualTo(1.0);
    }

    @Test
    void countsSignalingDeliveredAndRejected() {
        repository.current = acceptedCall();

        service.signal(CALLER_ID, repository.current.id(), Map.of("type", "offer", "sdp", "v=0"));
        assertThat(counter("ababawa.calls.signaling",
                "event", "offer", "result", "success", "reason", "none")).isEqualTo(1.0);

        assertThatThrownBy(() -> service.signal(CALLER_ID, repository.current.id(),
                Map.of("type", "bogus")))
                .isInstanceOf(AbvException.class);
        assertThat(counter("ababawa.calls.signaling",
                "event", "unknown", "result", "failure", "reason", "invalid_payload")).isEqualTo(1.0);

        repository.current = endedCall();
        assertThatThrownBy(() -> service.signal(CALLER_ID, repository.current.id(),
                Map.of("type", "candidate", "candidate", "candidate:1")))
                .isInstanceOf(AbvException.class);
        assertThat(counter("ababawa.calls.signaling",
                "event", "candidate", "result", "failure", "reason", "call_finished")).isEqualTo(1.0);
    }

    @Test
    void countsRejectedAndCanceledCall() {
        Call created = service.start(CALLER_ID,
                new CallService.StartInput(CALLEE_ID, "audio", CONVERSATION_ID));
        service.reject(CALLEE_ID, created.id(), "busy");

        repository.current = ringingCall();
        service.end(CALLER_ID, repository.current.id(), "cancel");

        assertThat(counter("ababawa.calls.ended", "status", "rejected")).isEqualTo(1.0);
        assertThat(counter("ababawa.calls.ended", "status", "canceled")).isEqualTo(1.0);
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static AbvProperties loadProps() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        ConfigurationPropertySource source = ConfigurationPropertySources.from(sources).iterator().next();
        return new Binder(source).bind("abv", Bindable.of(AbvProperties.class))
                .orElseThrow(() -> new AssertionError("abv section is missing in application.yml"));
    }

    private static ObjectProvider<Broadcaster> provider(Broadcaster broadcaster) {
        return new ObjectProvider<>() {
            @Override
            public Broadcaster getObject() {
                return broadcaster;
            }

            @Override
            public Broadcaster getObject(Object... args) {
                return broadcaster;
            }

            @Override
            public Broadcaster getIfAvailable() {
                return broadcaster;
            }

            @Override
            public Broadcaster getIfUnique() {
                return broadcaster;
            }
        };
    }

    private static Call ringingCall() {
        return new Call(UUID.randomUUID(), CONVERSATION_ID, CALLER_ID, CALLEE_ID, CallKind.AUDIO,
                CallStatus.RINGING, Instant.now(), null, null, null, null, 0, null);
    }

    private static Call acceptedCall() {
        Call ringing = ringingCall();
        return new Call(ringing.id(), CONVERSATION_ID, CALLER_ID, CALLEE_ID, CallKind.AUDIO,
                CallStatus.ACCEPTED, ringing.createdAt(), Instant.now(), null, null, null, 0, null);
    }

    private static Call endedCall() {
        Call ringing = ringingCall();
        return new Call(ringing.id(), CONVERSATION_ID, CALLER_ID, CALLEE_ID, CallKind.AUDIO,
                CallStatus.ENDED, ringing.createdAt(), Instant.now(), Instant.now(), CALLER_ID,
                "hangup", 12, null);
    }

    /** Репозиторий звонков в памяти: держит один звонок и статус активности. */
    private static final class FakeCallRepository extends CallRepository {

        private final Map<UUID, Call> calls = new HashMap<>();
        private Call current;
        private Call busyForCallee;

        FakeCallRepository() {
            super(null, null);
        }

        @Override
        public Call create(CreateCallInput in) {
            Call created = new Call(UUID.randomUUID(), in.conversationId(), in.callerId(), in.calleeId(),
                    in.kind(), CallStatus.RINGING, Instant.now(), null, null, null, null, 0, null);
            calls.put(created.id(), created);
            current = created;
            return created;
        }

        @Override
        public Call byId(UUID id) {
            Call call = calls.get(id);
            if (call != null) {
                return call;
            }
            if (current != null && current.id().equals(id)) {
                return current;
            }
            throw AbvException.notFound("call not found");
        }

        @Override
        public Call activeForUser(UUID userId) {
            if (busyForCallee != null && busyForCallee.calleeId().equals(userId)) {
                return busyForCallee;
            }
            return null;
        }

        @Override
        public Call transition(TransitionInput in) {
            Call from = byId(in.callId());
            boolean terminal = in.to().terminal();
            Call updated = new Call(from.id(), from.conversationId(), from.callerId(), from.calleeId(),
                    from.kind(), in.to(), from.createdAt(),
                    in.to() == CallStatus.ACCEPTED ? Instant.now() : from.answeredAt(),
                    terminal ? Instant.now() : from.endedAt(),
                    terminal ? in.actorId() : from.endedBy(),
                    terminal ? in.endReason() : from.endReason(),
                    in.to() == CallStatus.ENDED ? 12 : from.durationSeconds(),
                    null);
            calls.put(updated.id(), updated);
            current = updated;
            return updated;
        }

        @Override
        public void logEvent(UUID callId, UUID actorId, String type, Map<String, Object> payload) {
            // журнал событий для метрик не важен
        }
    }

    /** Профили участников; {@code unknown} можно объявить несуществующим. */
    private static final class FakeUsers implements UserLookup {

        private UUID unknown;

        @Override
        public User byId(UUID id) {
            if (id.equals(unknown)) {
                throw AbvException.notFound("user you are calling does not exist");
            }
            return user(id);
        }

        @Override
        public User findById(UUID id) {
            return user(id);
        }

        @Override
        public void touchLastSeen(UUID id) {
            // не требуется
        }

        private static User user(UUID id) {
            return new User(id, id + "@example.com", "user", null, Instant.now(), null);
        }
    }

    /** Чат: участники личного диалога; остальные команды не используются. */
    private static final class FakeChat implements ChatCommands {

        @Override
        public SendResult sendMessage(UUID actor, UUID conversationId, String body, String clientId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markRead(UUID actor, UUID conversationId, UUID messageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void typing(UUID actor, UUID conversationId, String state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> peersOf(UUID userId) {
            return List.of();
        }

        @Override
        public List<UUID> memberIds(UUID conversationId) {
            return List.of(CALLER_ID, CALLEE_ID);
        }

        @Override
        public DirectResult ensureDirect(UUID actor, UUID peerId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Push-устройств нет: FCM не мешает метрикам. */
    private static final class FakePushDevices extends PushDeviceRepository {

        FakePushDevices() {
            super(null);
        }

        @Override
        public boolean hasDevices(UUID userId) {
            return false;
        }

        @Override
        public List<String> tokens(UUID userId) {
            return List.of();
        }
    }

    /** Broadcaster, у которого получатель всегда онлайн (иначе звонок сразу missed). */
    private static final class RecordingBroadcaster implements Broadcaster {

        @Override
        public void sendToUsers(Collection<UUID> userIds, Events.Envelope event) {
            // рассылка для метрик не важна
        }

        @Override
        public boolean isOnline(UUID userId) {
            return true;
        }
    }
}
