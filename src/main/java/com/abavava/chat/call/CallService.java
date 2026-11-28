package com.abavava.chat.call;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.contract.Broadcaster;
import com.abavava.chat.contract.CallCommands;
import com.abavava.chat.contract.ChatCommands;
import com.abavava.chat.contract.NoopBroadcaster;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Состояние звонков, таймеры дозвона и транзит WebRTC-сигналинга
 * (аналог call/service.go). Медиатрафик через сервер не идёт: сервер хранит
 * состояние, рассылает события и пересылает SDP/ICE второму участнику.
 *
 * <p>Зависимости от соседних модулей — только через contract/: {@link UserLookup},
 * {@link ChatCommands}, {@link Broadcaster}. Broadcaster берётся лениво через
 * {@link ObjectProvider}, потому что realtime-слой (Hub) зависит от
 * CallCommands и при старте контекста возникает цикл.
 */
@Service
public class CallService implements CallCommands {

    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    static final String REASON_HANGUP = "hangup";
    static final String REASON_REJECTED = "rejected";
    static final String REASON_TIMEOUT = "timeout";

    /** Параметры инициации звонка (Go: StartInput). kind — «сырая» строка из JSON. */
    public record StartInput(UUID calleeId, String kind, UUID conversationId) {
    }

    private record CallParticipants(User caller, User callee) {
    }

    private final CallRepository repo;
    private final UserLookup users;
    private final ChatCommands chat;
    private final ObjectProvider<Broadcaster> broadcasterProvider;
    private final PushDeviceRepository pushDevices;
    private final FcmPushSender pushSender;
    private final BusinessMetrics metrics;
    /** Таймаут дозвона (abv.call-ring-timeout). */
    private final Duration ringTimeout;

    /** Один поток на все таймеры дозвона: порядок срабатываний предсказуем. */
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, ScheduledFuture<?>> ringTimers = new HashMap<>();
    private boolean closed;

    public CallService(CallRepository repo,
                       UserLookup users,
                       ChatCommands chat,
                       ObjectProvider<Broadcaster> broadcasterProvider,
                       PushDeviceRepository pushDevices,
                       FcmPushSender pushSender,
                       AbvProperties properties,
                       BusinessMetrics metrics) {
        this.repo = repo;
        this.users = users;
        this.chat = chat;
        this.broadcasterProvider = broadcasterProvider;
        this.pushDevices = pushDevices;
        this.pushSender = pushSender;
        this.metrics = metrics;
        // Валидность (положительный Duration) проверяется в AbvProperties.validate().
        this.ringTimeout = properties.callRingTimeout();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "call-ring-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ start

    /**
     * Создаёт звонок, уведомляет участников и взводит таймер дозвона.
     * Если вызываемый не в сети, звонок сразу фиксируется как missed.
     */
    public Call start(UUID actor, StartInput in) {
        CallParticipants participants;
        Call created;
        try {
            CallKind kind = validateStartInput(actor, in);
            participants = loadParticipants(actor, in.calleeId());
            ensureAvailable(actor, in.calleeId());
            UUID conversationId = resolveConversationId(actor, in.calleeId(), in.conversationId());
            created = createCall(actor, in.calleeId(), conversationId, kind);
            metrics.callCreated(created.kind().json());
        } catch (AbvException e) {
            metrics.callStart(BusinessMetrics.FAILURE, startFailureReason(e));
            throw e;
        } catch (RuntimeException e) {
            metrics.callStart(BusinessMetrics.FAILURE, "internal_error");
            throw e;
        }
        metrics.callStart(BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        return notifyCallStarted(created, actor, participants);
    }

    private static CallKind validateStartInput(UUID actor, StartInput in) {
        if (in.calleeId() == null) {
            throw AbvException.invalidField("callee_id", "callee_id is required");
        }
        if (in.calleeId().equals(actor)) {
            throw AbvException.invalid("cannot call yourself");
        }
        return parseKind(in.kind());
    }

    private CallParticipants loadParticipants(UUID callerId, UUID calleeId) {
        User caller;
        try {
            caller = users.byId(callerId);
        } catch (RuntimeException e) {
            throw AbvException.internal(e);
        }

        User callee;
        try {
            callee = users.byId(calleeId);
        } catch (RuntimeException e) {
            throw AbvException.notFound("user you are calling does not exist");
        }
        return new CallParticipants(caller, callee);
    }

    private void ensureAvailable(UUID callerId, UUID calleeId) {
        // Один активный звонок на пользователя в любой роли: частичные уникальные
        // индексы ловят только совпадение роли, поэтому проверяем явно.
        if (repo.activeForUser(callerId) != null) {
            throw AbvException.conflict("you are already in a call");
        }
        if (repo.activeForUser(calleeId) != null) {
            throw AbvException.conflict("the person you are calling is busy");
        }
    }

    private UUID resolveConversationId(UUID callerId, UUID calleeId, UUID conversationId) {
        if (conversationId != null) {
            List<UUID> members = chat.memberIds(conversationId);
            if (members == null || !members.contains(callerId) || !members.contains(calleeId)) {
                throw AbvException.notFound("conversation not found");
            }
            return conversationId;
        }
        ChatCommands.DirectResult direct = chat.ensureDirect(callerId, calleeId);
        return direct.view().conversation().id();
    }

    private Call createCall(UUID callerId, UUID calleeId, UUID conversationId, CallKind kind) {
        Call created = repo.create(new CallRepository.CreateCallInput(
                conversationId, callerId, calleeId, kind));
        repo.logEvent(created.id(), callerId, "created", Map.of("kind", kind.json()));
        return created;
    }

    private Call notifyCallStarted(Call created, UUID callerId, CallParticipants participants) {
        Broadcaster broadcaster = broadcaster();
        broadcaster.sendToUsers(List.of(callerId), Events.newEvent(Events.CALL_CREATED,
                new Events.CallPayload(created.withPeer(participants.callee()))));

        UUID calleeId = created.calleeId();
        boolean canPush = pushSender.enabled() && pushDevices.hasDevices(calleeId);
        if (!broadcaster.isOnline(calleeId) && !canPush) {
            return markCallMissed(created, callerId);
        }

        broadcaster.sendToUsers(List.of(calleeId), Events.newEvent(Events.CALL_INCOMING,
                new Events.CallPayload(created.withPeer(participants.caller()))));
        if (canPush) {
            sendIncomingCallPushes(created, participants.caller());
        }
        armRingTimer(created.id());
        return created;
    }

    private Call markCallMissed(Call created, UUID callerId) {
        Call missed = repo.transition(new CallRepository.TransitionInput(
                created.id(), CallStatus.RINGING, CallStatus.MISSED, null, REASON_TIMEOUT));
        repo.logEvent(created.id(), null, "missed", Map.of("reason", "callee offline"));
        metrics.callEnded(missed.status().json());
        sendCallEvent(Events.CALL_UPDATED, missed, List.of(callerId));
        return missed;
    }

    private void sendIncomingCallPushes(Call call, User caller) {
        for (String token : pushDevices.tokens(call.calleeId())) {
            try {
                pushSender.sendIncomingCall(token, call, caller.nickname());
            } catch (RuntimeException e) {
                log.warn("incoming call push failed: call_id={} callee_id={}",
                        call.id(), call.calleeId(), e);
            }
        }
    }

    // ------------------------------------------------- accept / reject / end

    /** Принимает входящий звонок (только вызываемый). */
    @Override
    public Call accept(UUID actor, UUID callId) {
        Call current = repo.byId(callId);
        if (!current.calleeId().equals(actor)) {
            throw AbvException.forbidden("only the callee can accept the call");
        }
        Call updated = repo.transition(new CallRepository.TransitionInput(
                callId, CallStatus.RINGING, CallStatus.ACCEPTED, actor, null));
        cancelRingTimer(callId);
        repo.logEvent(callId, actor, "accepted", null);
        metrics.callAccepted(updated.kind().json());
        sendCallEvent(Events.CALL_UPDATED, updated, participants(updated));
        return updated;
    }

    /** Отклоняет входящий звонок (только вызываемый). */
    @Override
    public Call reject(UUID actor, UUID callId, String reason) {
        Call current = repo.byId(callId);
        if (!current.calleeId().equals(actor)) {
            throw AbvException.forbidden("only the callee can reject the call");
        }
        Call updated = repo.transition(new CallRepository.TransitionInput(
                callId, CallStatus.RINGING, CallStatus.REJECTED, actor,
                defaultReason(reason, REASON_REJECTED)));
        cancelRingTimer(callId);
        repo.logEvent(callId, actor, "rejected", null);
        metrics.callEnded(updated.status().json());
        sendCallEvent(Events.CALL_UPDATED, updated, participants(updated));
        return updated;
    }

    /**
     * Завершает звонок. Кто именно повесил трубку, зависит от состояния:
     * звонящий во время дозвона — отмена, вызываемый во время дозвона —
     * отклонение, любой в разговоре — завершение.
     */
    @Override
    public Call end(UUID actor, UUID callId, String reason) {
        Call current = repo.byId(callId);
        if (!current.involved(actor)) {
            throw AbvException.forbidden("you are not a participant of this call");
        }

        CallStatus target;
        CallStatus from = current.status();
        if (from == CallStatus.RINGING) {
            target = current.callerId().equals(actor) ? CallStatus.CANCELED : CallStatus.REJECTED;
        } else if (from == CallStatus.ACCEPTED) {
            target = CallStatus.ENDED;
        } else {
            throw AbvException.conflict("call is already %s", from.json());
        }

        Call updated = repo.transition(new CallRepository.TransitionInput(
                callId, from, target, actor, defaultReason(reason, REASON_HANGUP)));
        cancelRingTimer(callId);
        repo.logEvent(callId, actor, "ended", Map.of("status", target.json()));
        metrics.callEnded(updated.status().json());
        if (updated.status() == CallStatus.ENDED) {
            metrics.callDuration(updated.kind().json(), updated.durationSeconds());
        }
        sendCallEvent(Events.CALL_UPDATED, updated, participants(updated));
        return updated;
    }

    // ---------------------------------------------------------------- signal

    /**
     * Транзитом передаёт SDP/ICE второму участнику. Содержимое payload сервер
     * не разбирает — только проверяет права и активность звонка.
     */
    @Override
    public void signal(UUID actor, UUID callId, Map<String, Object> payload) {
        String event = signalingEvent(payload);
        try {
            if (payload == null || payload.isEmpty()) {
                countSignalingFailure(event, "invalid_payload");
                throw AbvException.invalidField("payload", "payload is required");
            }
            Object rawType = payload.get("type");
            String type = rawType instanceof String s ? s : "";
            if (!"offer".equals(type) && !"answer".equals(type) && !"candidate".equals(type)) {
                countSignalingFailure(event, "invalid_payload");
                throw AbvException.invalidField("payload.type",
                        "payload.type must be \"offer\", \"answer\" or \"candidate\"");
            }

            Call current = repo.byId(callId);
            if (!current.involved(actor)) {
                countSignalingFailure(event, "not_participant");
                throw AbvException.forbidden("you are not a participant of this call");
            }
            if (current.status().terminal()) {
                countSignalingFailure(event, "call_finished");
                throw AbvException.conflict("call is already %s", current.status().json());
            }

            UUID peer = current.party(actor);
            Broadcaster broadcaster = broadcaster();
            if (!broadcaster.isOnline(peer)) {
                log.warn("signal for offline peer dropped: call_id={} peer_id={}", callId, peer);
                countSignalingFailure(event, "peer_offline");
                throw AbvException.conflict("the other participant is offline");
            }
            broadcaster.sendToUsers(List.of(peer),
                    Events.newEvent(Events.CALL_SIGNAL, new Events.SignalPayload(callId, actor, payload)));
            metrics.callSignaling(event, BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        } catch (AbvException e) {
            // Причина уже посчитана в точке отказа; здесь только проброс.
            if (e.code() == AbvException.Code.NOT_FOUND) {
                countSignalingFailure(event, "not_found");
            }
            throw e;
        } catch (RuntimeException e) {
            countSignalingFailure(event, "internal_error");
            throw e;
        }
    }

    private void countSignalingFailure(String event, String reason) {
        metrics.callSignaling(event, BusinessMetrics.FAILURE, reason);
    }

    /** Тип кадра сигналинга для метрики: offer/answer/candidate, иначе unknown. */
    private static String signalingEvent(Map<String, Object> payload) {
        Object rawType = payload == null ? null : payload.get("type");
        String type = rawType instanceof String s ? s : "";
        return switch (type) {
            case "offer", "answer", "candidate" -> type;
            default -> "unknown";
        };
    }

    // --------------------------------------------------- чтение состояния

    /** История звонков с профилем собеседника (удалённый профиль пропускаем). */
    public List<Call> history(UUID actor, int limit) {
        List<Call> calls = repo.history(actor, limit);
        Set<UUID> peers = new LinkedHashSet<>();
        for (Call c : calls) {
            peers.add(c.party(actor));
        }
        Map<UUID, User> profiles = new HashMap<>();
        for (UUID id : peers) {
            User profile = users.findById(id);
            if (profile != null) {
                profiles.put(id, profile);
            }
        }
        List<Call> out = new ArrayList<>(calls.size());
        for (Call c : calls) {
            out.add(c.withPeer(profiles.get(c.party(actor))));
        }
        return out;
    }

    /** Звонок участнику; постороннему — not_found (не раскрываем существование). */
    public Call get(UUID actor, UUID callId) {
        Call call = repo.byId(callId);
        if (!call.involved(actor)) {
            throw AbvException.notFound("call not found");
        }
        User peer = users.findById(call.party(actor));
        return peer == null ? call : call.withPeer(peer);
    }

    /** Активный звонок пользователя: нужен после переподключения по WS. */
    public Call activeForUser(UUID actor) {
        return repo.activeForUser(actor);
    }

    /** Закрывает звонки, «зависшие» после перезапуска сервиса. */
    public void reconcile() {
        try {
            long count = repo.reconcileMissed(ringTimeout);
            if (count > 0) {
                log.info("reconciled ringing calls: count={}", count);
            }
        } catch (RuntimeException e) {
            log.error("reconcile ringing calls", e);
        }
    }

    // --------------------------------------------------------------- таймеры

    /**
     * Останавливает все таймеры дозвона и освобождает поток.
     * Вызывается из {@code call.CallReconcileRunner#shutdown()} (@PreDestroy).
     */
    public void close() {
        synchronized (ringTimers) {
            closed = true;
            for (ScheduledFuture<?> timer : ringTimers.values()) {
                timer.cancel(false);
            }
            ringTimers.clear();
        }
        scheduler.shutdownNow();
    }

    private void armRingTimer(UUID callId) {
        synchronized (ringTimers) {
            if (closed) {
                return;
            }
            ScheduledFuture<?> timer = scheduler.schedule(
                    () -> onRingTimeout(callId), ringTimeout.toMillis(), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> old = ringTimers.put(callId, timer);
            if (old != null) {
                old.cancel(false);
            }
        }
    }

    private void cancelRingTimer(UUID callId) {
        synchronized (ringTimers) {
            ScheduledFuture<?> timer = ringTimers.remove(callId);
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    /** Переводит неотвеченный звонок в missed; гонка с accept/end — норма. */
    private void onRingTimeout(UUID callId) {
        synchronized (ringTimers) {
            ringTimers.remove(callId);
            if (closed) {
                return;
            }
        }
        try {
            Call updated = repo.transition(new CallRepository.TransitionInput(
                    callId, CallStatus.RINGING, CallStatus.MISSED, null, REASON_TIMEOUT));
            repo.logEvent(callId, null, "missed", Map.of("reason", "timeout"));
            metrics.callEnded(updated.status().json());
            sendCallEvent(Events.CALL_UPDATED, updated, participants(updated));
        } catch (AbvException e) {
            if (e.code() == AbvException.Code.CONFLICT) {
                return; // уже принят или завершён — это нормальная гонка
            }
            log.error("ring timeout transition: call_id={}", callId, e);
        } catch (RuntimeException e) {
            log.error("ring timeout transition: call_id={}", callId, e);
        }
    }

    // -------------------------------------------------------------- рассылка

    /** Broadcaster или no-op, если realtime-слой ещё не собран. */
    private Broadcaster broadcaster() {
        return broadcasterProvider.getIfAvailable(() -> NoopBroadcaster.INSTANCE);
    }

    /**
     * Рассылает событие звонка указанным получателям, подставляя каждому
     * профиль собеседника в поле peer.
     */
    private void sendCallEvent(String eventType, Call call, Collection<UUID> recipients) {
        Broadcaster broadcaster = broadcaster();
        for (UUID recipient : recipients) {
            Call payload = call;
            UUID peerId = call.party(recipient);
            User peer = users.findById(peerId);
            if (peer != null) {
                payload = call.withPeer(peer);
            } else {
                log.warn("load peer profile for call event: peer_id={}", peerId);
            }
            try {
                broadcaster.sendToUsers(List.of(recipient),
                        Events.newEvent(eventType, new Events.CallPayload(payload)));
            } catch (RuntimeException e) {
                // Транспорт вторичен: смена состояния уже зафиксирована в БД.
                log.warn("send call event: call_id={} type={} user_id={}",
                        call.id(), eventType, recipient, e);
            }
        }
    }

    private static List<UUID> participants(Call call) {
        return List.of(call.callerId(), call.calleeId());
    }

    /** "" | null → audio; иначе audio|video; прочее — валидационная ошибка. */
    private static CallKind parseKind(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return CallKind.AUDIO;
        }
        try {
            return CallKind.fromJson(value);
        } catch (IllegalArgumentException e) {
            throw AbvException.invalidField("kind", "kind must be \"audio\" or \"video\"");
        }
    }

    private static String defaultReason(String reason, String fallback) {
        if (reason == null) {
            return fallback;
        }
        String trimmed = reason.strip();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /**
     * Причина отказа создания звонка из закрытого набора метрик
     * (docs/METRICS.md). «Нельзя звонить самому себе» — отдельное значение
     * {@code self_call}, остальные невалидные входы — {@code invalid_input}.
     */
    private static String startFailureReason(AbvException e) {
        return switch (e.code()) {
            case INVALID_REQUEST -> "cannot call yourself".equals(e.getMessage())
                    ? "self_call" : "invalid_input";
            case VALIDATION_FAILED -> "invalid_input";
            case NOT_FOUND -> "callee_not_found";
            case CONFLICT -> "busy";
            default -> "internal_error";
        };
    }
}
