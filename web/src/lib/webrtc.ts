/**
 * WebRTC 1:1 с perfect negotiation (docs/WS-PROTOCOL.md, раздел «WebRTC-сигналинг»).
 *
 * Роли: `polite = (myUserId > peerUserId)` — формула симметрична, поэтому обе
 * стороны вычисляют её независимо и получают ровно одного polite и одного impolite.
 * Инициатор звонка обычно impolite, но при коллизии offer'ов решает именно формула.
 *
 * Схема установления соединения (детерминированная, без лишней glare):
 *  - звонящий: getUserMedia → addTrack → negotiationneeded → offer;
 *  - вызываемый: getUserMedia → ждёт offer → setRemoteDescription(offer) →
 *    addTrack (переиспользует transceiver'ы из offer'а) → answer.
 *    Треки добавляются строго после setRemoteDescription, иначе addTrack породил бы
 *    собственный offer и встречный offer-коллизию на ровном месте.
 *
 * Восстановление ICE, статистика и телеметрия вынесены в отдельные модули
 * (ice-recovery.ts, call-stats.ts, telemetry.ts): CallSession остаётся
 * единственной точкой жизненного цикла RTCPeerConnection.
 */
import { configApi } from './api';
import { audioTrackConstraints, rememberedMicId, rememberMicId, shouldFallbackToDefaultMic } from './media-devices';
import { loadCallConfig, normalizeCallConfig } from './call-config';
import type { CallRuntimeConfig } from './call-config';
import { StatsAggregator, emptyStatsSummary, isDegraded } from './call-stats';
import type { CallStatsSummary } from './call-stats';
import { IceRecoveryController } from './ice-recovery';
import type {
  IceAttemptFailedInfo,
  IceAttemptInfo,
  IceExhaustedInfo,
  IceRecoveredInfo,
  IceRecoveryTimers,
} from './ice-recovery';
import { SessionNegotiator } from './negotiation';
import type { NegotiationSignal } from './negotiation';
import type { CallTelemetry, PeerRole } from './telemetry';
import type { CallKind, IceServer, SignalPayload } from './types';

export type { CallQuality, CallStatsSummary } from './call-stats';

export const DEFAULT_ICE_SERVERS: RTCIceServer[] = [{ urls: 'stun:stun.l.google.com:19302' }];

function toRtcIceServer(server: IceServer): RTCIceServer | null {
  const urls = Array.isArray(server.urls) ? server.urls.filter(Boolean) : server.urls;
  if (!urls || (Array.isArray(urls) && urls.length === 0)) return null;
  const result: RTCIceServer = { urls };
  if (server.username) result.username = server.username;
  if (server.credential) result.credential = server.credential;
  return result;
}

let cachedIceServers: RTCIceServer[] | null = null;

/** ICE-серверы из GET /config/ice; при любой ошибке — встроенный STUN-дефолт. */
export async function loadIceServers(): Promise<RTCIceServer[]> {
  if (cachedIceServers) return cachedIceServers;
  try {
    const config = await configApi.ice();
    const servers = (config.ice_servers ?? [])
      .map(toRtcIceServer)
      .filter((item): item is RTCIceServer => item !== null);
    cachedIceServers = servers.length > 0 ? servers : DEFAULT_ICE_SERVERS;
  } catch {
    cachedIceServers = DEFAULT_ICE_SERVERS;
  }
  return cachedIceServers;
}

export interface CallSessionOptions {
  callId: string;
  kind: CallKind;
  myUserId: string;
  peerUserId: string;
  /** Идентификатор WebRTC-сессии; новый RTCPeerConnection — новый sessionId. */
  sessionId?: string;
  /** Роль в звонке для телеметрии: caller/callee. */
  peerRole?: PeerRole;
  /** Переопределения таймаутов/частот (в тестах и для нестандартных сетей). */
  config?: Partial<CallRuntimeConfig>;
  /** Клиентская телеметрия; null — не отправлять. */
  telemetry?: CallTelemetry | null;
  /** Отправка call.signal с payload. */
  sendSignal: (payload: SignalPayload) => void;
  onRemoteStream: (stream: MediaStream) => void;
  onLocalStream: (stream: MediaStream) => void;
  onConnectionState: (state: RTCPeerConnectionState) => void;
  onError: (message: string) => void;
  /** Восстановление ICE исчерпало попытки: понятная причина для UI. */
  onConnectionRecoveryFailed?: (reason: string) => void;
  /** Камера физически включена/выключена (для индикатора и кнопки). */
  onCameraEnabledChange?: (enabled: boolean) => void;
  /** Диагностический callback: новая сводка статистики. */
  onStats?: (stats: CallStatsSummary) => void;
}

const STATS_MAX_BACKOFF_MS = 30_000;

/** Новый идентификатор WebRTC-сессии: каждый RTCPeerConnection получает свой. */
export function createSessionId(): string {
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') return cryptoObj.randomUUID();
  return `sess-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function describeMediaError(error: unknown): string {
  const name = error instanceof DOMException ? error.name : '';
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return 'Доступ к микрофону или камере запрещён';
    case 'NotFoundError':
    case 'OverconstrainedError':
      return 'Устройство не найдено';
    case 'NotReadableError':
      return 'Устройство занято другим приложением';
    default:
      return error instanceof Error ? error.message : 'Не удалось получить доступ к медиа';
  }
}

export class CallSession {
  readonly callId: string;
  readonly sessionId: string;
  readonly kind: CallKind;
  readonly polite: boolean;
  readonly peerRole: PeerRole;
  readonly remoteStream = new MediaStream();

  private readonly pc: RTCPeerConnection;
  private readonly options: CallSessionOptions;
  private readonly config: CallRuntimeConfig;
  private readonly telemetry: CallTelemetry | null;
  private readonly negotiator: SessionNegotiator;
  private readonly recovery: IceRecoveryController;
  private readonly statsAggregator = new StatsAggregator();

  private localStream: MediaStream | null = null;

  private closed = false;
  private statsTimer: number | null = null;
  private statsStarted = false;
  private statsInFlight = false;
  private latestStats: CallStatsSummary;
  private previousDegraded = false;
  private lastStatsTelemetryAt = 0;

  private cameraEnabled = false;
  private currentConnectionState: RTCPeerConnectionState;
  private restartAttempt = 0;
  private restartAttemptId: string | null = null;
  private readonly sessionStartedAt = Date.now();

  constructor(options: CallSessionOptions, iceServers: RTCIceServer[]) {
    this.callId = options.callId;
    this.sessionId = options.sessionId ?? createSessionId();
    this.kind = options.kind;
    this.options = options;
    this.peerRole = options.peerRole ?? (options.myUserId > options.peerUserId ? 'callee' : 'caller');
    this.config = normalizeCallConfig(loadCallConfig(options.config));
    this.telemetry = options.telemetry ?? null;
    this.telemetry?.beginSession(this.sessionId);
    this.latestStats = emptyStatsSummary(this.config.statsIntervalMs);

    // Строковое сравнение UUID: одинаковый результат на обеих сторонах.
    this.polite = options.myUserId > options.peerUserId;

    this.pc = new RTCPeerConnection({
      iceServers,
      bundlePolicy: 'max-bundle',
      iceCandidatePoolSize: 2,
    });
    this.currentConnectionState = this.pc.connectionState;
    this.telemetry?.event('session_started', {
      details: {
        kind: this.kind,
        polite: this.polite,
        ice_server_count: iceServers.length,
        bundle_policy: 'max-bundle',
      },
      statsIntervalMs: this.config.statsIntervalMs,
    });

    this.negotiator = new SessionNegotiator(
      this.pc,
      { polite: this.polite },
      {
        sendSignal: (payload: NegotiationSignal) => this.sendSignal(payload),
        onError: (message) => this.options.onError(message),
        onRemoteOffer: () => this.attachPreparedTracks(),
        onAnswerApplied: () => {
          // Ответ применён: restart больше не «в полёте», но фактическое
          // восстановление подтверждает connected/attempt timeout.
        },
        onOfferCollision: ({ polite, action, remoteUfrag }) => {
          this.telemetry?.event('offer_collision', {
            details: { polite, action, remote_ufrag_present: remoteUfrag !== null },
            restartAttempt: this.restartAttempt,
            restartAttemptId: this.restartAttemptId,
          });
        },
        onIceRestartFailed: (error) => {
          this.recovery.attemptFailed(error);
        },
        onIgnoredCandidate: (reason) => {
          this.telemetry?.event('candidate_ignored', {
            details: { reason },
            priority: 'low',
            restartAttempt: this.restartAttempt,
            restartAttemptId: this.restartAttemptId,
          });
        },
      },
    );

    this.recovery = new IceRecoveryController(
      {
        disconnectedGraceMs: this.config.disconnectedGraceMs,
        attemptTimeoutMs: this.config.iceRestartAttemptTimeoutMs,
        maxAttempts: this.config.iceRestartMaxAttempts,
        backoffBaseMs: this.config.iceRestartBackoffBaseMs,
        backoffMaxMs: this.config.iceRestartBackoffMaxMs,
      },
      {
        onAttempt: (info) => this.onIceRestartAttempt(info),
        onAttemptFailed: (info) => this.onIceAttemptFailed(info),
        onRecovered: (info) => this.onIceRecoverySucceeded(info),
        onExhausted: (info) => this.onIceRecoveryExhausted(info),
        onPhaseChange: (phase, detail) => {
          if (phase === 'backoff' || phase === 'waiting') {
            this.telemetry?.event('ice_state_changed', {
              details: { phase, reason: detail.reason, attempt: detail.attempt },
              priority: 'low',
              restartAttempt: detail.attempt,
              restartAttemptId: this.restartAttemptId,
            });
          }
        },
      },
      {
        createId: createSessionId,
        isConnected: () => this.pc.connectionState === 'connected',
      } satisfies { createId: () => string; isConnected: () => boolean; timers?: IceRecoveryTimers },
    );

    this.pc.onicecandidate = (event) => {
      // Пустой candidate (end-of-candidates) не пересылаем — так требует контракт.
      if (!event.candidate || !event.candidate.candidate) return;
      this.sendSignal({
        type: 'candidate',
        candidate: event.candidate.candidate,
        sdp_mid: event.candidate.sdpMid ?? null,
        sdp_mline_index: event.candidate.sdpMLineIndex ?? null,
        username_fragment: event.candidate.usernameFragment ?? null,
      });
    };

    this.pc.ontrack = (event) => {
      const isNewTrack = !this.remoteStream.getTracks().some((track) => track.id === event.track.id);
      if (isNewTrack) {
        this.remoteStream.addTrack(event.track);
        // MediaStream остаётся тем же объектом, когда браузер добавляет в него
        // вторую дорожку (часто видео после аудио). Публикуем новый wrapper,
        // иначе React/Zustand могут не перерисовать VideoSurface из-за той же
        // ссылки, и видео проявится только после следующего нажатия в UI.
        this.options.onRemoteStream(new MediaStream(this.remoteStream.getTracks()));
      }
    };

    this.pc.onnegotiationneeded = () => {
      void this.negotiator.negotiationNeeded();
    };

    this.pc.onconnectionstatechange = () => {
      this.handleConnectionState(this.pc.connectionState);
    };

    this.pc.oniceconnectionstatechange = () => {
      this.telemetry?.event('ice_state_changed', {
        details: {
          ice_connection_state: this.pc.iceConnectionState,
          connection_state: this.pc.connectionState,
        },
        priority: 'low',
        restartAttempt: this.restartAttempt,
        restartAttemptId: this.restartAttemptId,
      });
    };
  }

  static async create(options: CallSessionOptions): Promise<CallSession> {
    const iceServers = await loadIceServers();
    return new CallSession(options, iceServers);
  }

  get isClosed(): boolean {
    return this.closed;
  }

  getSessionId(): string {
    return this.sessionId;
  }

  getPeerRole(): PeerRole {
    return this.peerRole;
  }

  getRestartAttempt(): number {
    return this.restartAttempt;
  }

  getRestartAttemptId(): string | null {
    return this.restartAttemptId;
  }

  getLocalStream(): MediaStream | null {
    return this.localStream;
  }

  getConnectionState(): RTCPeerConnectionState {
    return this.pc.connectionState;
  }

  isCameraEnabled(): boolean {
    return this.cameraEnabled;
  }

  isMuted(): boolean {
    return (this.localStream?.getAudioTracks() ?? []).every((track) => !track.enabled);
  }

  /** Роль звонящего: получаем медиа, добавляем треки — offer уйдёт сам по negotiationneeded. */
  async startAsCaller(): Promise<void> {
    this.localStream = await this.acquireMedia(this.kind === 'video');
    this.cameraEnabled = this.localStream.getVideoTracks().length > 0;
    this.options.onLocalStream(this.localStream);
    this.options.onCameraEnabledChange?.(this.cameraEnabled);
    for (const track of this.localStream.getTracks()) this.pc.addTrack(track, this.localStream);
  }

  /**
   * Роль вызываемого: получаем медиа, но треки добавляем позже — внутри обработки
   * входящего offer'а (см. applyOffer), чтобы переиспользовать его transceiver'ы.
   */
  async prepareAsAnswerer(): Promise<void> {
    this.localStream = await this.acquireMedia(this.kind === 'video');
    this.cameraEnabled = this.localStream.getVideoTracks().length > 0;
    this.options.onLocalStream(this.localStream);
    this.options.onCameraEnabledChange?.(this.cameraEnabled);
  }

  /** Обработка call.signal: offer/answer/candidate по правилам perfect negotiation. */
  async handleSignal(payload: SignalPayload): Promise<void> {
    if (this.closed) return;
    await this.negotiator.handleSignal(payload);
  }

  /** Включить камеру: новый трек → renegotiation (offer/answer), соединение не пересобираем. */
  async enableCamera(): Promise<void> {
    if (this.closed) return;
    if (this.localStream?.getVideoTracks().length) {
      for (const track of this.localStream.getVideoTracks()) track.enabled = true;
      this.cameraEnabled = true;
      this.options.onCameraEnabledChange?.(true);
      return;
    }
    try {
      const media = await navigator.mediaDevices.getUserMedia({ video: true });
      const [videoTrack] = media.getVideoTracks();
      if (!videoTrack) return;
      const stream = this.ensureLocalStream();
      stream.addTrack(videoTrack);
      this.pc.addTrack(videoTrack, stream);
      this.cameraEnabled = true;
      this.options.onLocalStream(stream);
      this.options.onCameraEnabledChange?.(true);
      // Дальше negotiationneeded сам отправит новый offer — соединение не пересобирается.
    } catch (error) {
      this.options.onError(describeMediaError(error));
    }
  }

  /**
   * Выключить камеру. Трек именно останавливаем (а не просто enabled=false),
   * чтобы погас аппаратный индикатор камеры.
   */
  disableCamera(): void {
    const stream = this.localStream;
    if (stream) {
      for (const track of stream.getVideoTracks()) {
        const sender = this.pc.getSenders().find((item) => item.track === track);
        if (sender) {
          try {
            this.pc.removeTrack(sender);
          } catch {
            // соединение могло уже закрыться
          }
        }
        track.stop();
        stream.removeTrack(track);
      }
    }
    this.cameraEnabled = false;
    this.options.onCameraEnabledChange?.(false);
    if (stream) this.options.onLocalStream(stream);
  }

  toggleCamera(): Promise<void> | void {
    if (this.cameraEnabled) {
      this.disableCamera();
      return;
    }
    return this.enableCamera();
  }

  mute(): void {
    for (const track of this.localStream?.getAudioTracks() ?? []) track.enabled = false;
  }

  unmute(): void {
    for (const track of this.localStream?.getAudioTracks() ?? []) track.enabled = true;
  }

  /**
   * Полное завершение: останавливаем статистику, отменяем таймеры ICE restart,
   * прекращаем приём телеметрии, закрываем RTCPeerConnection и очищаем контекст.
   */
  hangup(reason = 'hangup'): void {
    if (this.closed) return;
    this.closed = true;

    this.recovery.cancel(reason);
    this.negotiator.close();
    this.stopStatsPolling();
    this.statsAggregator.reset();

    const telemetry = this.telemetry;
    telemetry?.event('session_ended', {
      sessionId: this.sessionId,
      restartAttempt: this.restartAttempt,
      restartAttemptId: this.restartAttemptId,
      selectedCandidatePairId: this.latestStats.candidatePairId,
      statsIntervalMs: this.latestStats.statsIntervalMs,
      details: {
        reason,
        duration_ms: Math.max(0, Date.now() - this.sessionStartedAt),
        connection_state: this.pc.connectionState,
        ice_connection_state: this.pc.iceConnectionState,
        restart_attempts: this.restartAttempt,
        candidate_pair_id: this.latestStats.candidatePairId,
        local_candidate_type: this.latestStats.localCandidateType,
        remote_candidate_type: this.latestStats.remoteCandidateType,
      },
    });

    for (const track of this.localStream?.getTracks() ?? []) {
      try {
        track.stop();
      } catch {
        // трек мог быть уже остановлен
      }
    }
    this.localStream = null;
    this.cameraEnabled = false;
    this.options.onCameraEnabledChange?.(false);

    for (const track of this.remoteStream.getTracks()) {
      try {
        track.stop();
      } catch {
        // игнорируем
      }
    }

    try {
      this.pc.close();
    } catch {
      // peer connection мог быть уже закрыт
    }

    this.latestStats = emptyStatsSummary(this.config.statsIntervalMs);
    this.restartAttempt = 0;
    this.restartAttemptId = null;
  }

  /**
   * Принудительная renegotiation с ICE restart. Используется после реконнекта WS:
   * сигналинг мог потерять кандидатов, и соединение нужно пересобрать.
   */
  renegotiate(reason = 'ws_resync'): void {
    if (this.closed) return;
    this.telemetry?.event('ice_restart_requested', {
      details: {
        reason,
        connection_state: this.pc.connectionState,
        ice_connection_state: this.pc.iceConnectionState,
      },
      restartAttempt: this.restartAttempt,
      restartAttemptId: this.restartAttemptId,
    });
    this.recovery.requestRestart(reason);
  }

  /**
   * Восстановить первый offer вызывающего, если он был отправлен, пока второй
   * участник был офлайн (например, приложение запустили по FCM уведомлению).
   * Без rollback RTCPeerConnection остаётся в have-local-offer и не создаёт
   * новый negotiationneeded после того, как адресат подключился.
   */
  async recoverUnansweredOffer(): Promise<void> {
    if (this.closed || this.pc.remoteDescription) return;
    try {
      await this.negotiator.recoverUnansweredOffer();
    } catch (error) {
      this.options.onError(describeMediaError(error));
    }
  }

  stopStatsPolling(): void {
    this.statsStarted = false;
    if (this.statsTimer !== null) {
      window.clearTimeout(this.statsTimer);
      this.statsTimer = null;
    }
  }

  /* ---------------- статистика ---------------- */

  /** Последняя собранная сводка (не запускает новый getStats). */
  async stats(): Promise<CallStatsSummary> {
    if (this.closed) return emptyStatsSummary(this.config.statsIntervalMs);
    return this.latestStats;
  }

  /** Периодический опрос getStats для индикатора качества и телеметрии. */
  startStatsPolling(onStats: (stats: CallStatsSummary) => void): void {
    this.stopStatsPolling();
    this.statsStarted = true;
    this.options.onStats = onStats;
    this.scheduleStatsPolling(0);
  }

  private currentStatsInterval(): number {
    if (this.recovery.isRestarting()) return this.config.statsRestartIntervalMs;
    if (this.previousDegraded) return this.config.statsDegradedIntervalMs;
    return this.config.statsIntervalMs;
  }

  private scheduleStatsPolling(delayMs?: number): void {
    if (!this.statsStarted || this.closed || this.statsTimer !== null) return;
    const delay = delayMs ?? this.currentStatsInterval();
    this.statsTimer = window.setTimeout(() => {
      this.statsTimer = null;
      void this.collectStats();
    }, Math.max(0, delay));
  }

  private async collectStats(): Promise<void> {
    if (!this.statsStarted || this.closed || this.statsInFlight) return;
    this.statsInFlight = true;
    const intervalMs = this.currentStatsInterval();
    try {
      const report = await this.pc.getStats();
      if (this.closed) return;
      const summary = this.statsAggregator.sample(report, intervalMs, Date.now());
      this.applyStats(summary);
    } catch {
      // Браузер может бросить InvalidStateError сразу после close — просто
      // планируем следующий опрос, не ломая звонок.
    } finally {
      this.statsInFlight = false;
      if (this.statsStarted && !this.closed) {
        const next = this.currentStatsInterval();
        // При повторных ошибках не крутимся вхолостую.
        this.scheduleStatsPolling(this.latestStats.sampledAt === 0 ? STATS_MAX_BACKOFF_MS : next);
      }
    }
  }

  private applyStats(summary: CallStatsSummary): void {
    const previous = this.latestStats;
    this.latestStats = summary;
    this.options.onStats?.(summary);
    this.telemetry?.updateStatsInterval(summary.statsIntervalMs);
    this.telemetry?.updateSelectedCandidatePair(summary.candidatePairId);

    if (
      previous.candidatePairId &&
      summary.candidatePairId &&
      previous.candidatePairId !== summary.candidatePairId
    ) {
      this.telemetry?.event('candidate_pair_changed', {
        details: {
          previous_candidate_pair_id: previous.candidatePairId,
          next_candidate_pair_id: summary.candidatePairId,
          local_candidate_type: summary.localCandidateType,
          remote_candidate_type: summary.remoteCandidateType,
        },
        selectedCandidatePairId: summary.candidatePairId,
        statsIntervalMs: summary.statsIntervalMs,
        restartAttempt: this.restartAttempt,
        restartAttemptId: this.restartAttemptId,
      });
    }

    const degraded = isDegraded(summary);
    if (degraded && !this.previousDegraded) {
      this.telemetry?.event('stats_degraded', {
        details: {
          quality: summary.quality,
          rtt_ms: summary.rttMs,
          interval_loss_percent: summary.intervalLossPercent,
          bitrate_in_bps: summary.bitrateInBps,
          bitrate_out_bps: summary.bitrateOutBps,
          candidate_pair_id: summary.candidatePairId,
        },
        selectedCandidatePairId: summary.candidatePairId,
        statsIntervalMs: summary.statsIntervalMs,
        restartAttempt: this.restartAttempt,
        restartAttemptId: this.restartAttemptId,
      });
    } else if (!degraded && this.previousDegraded) {
      this.telemetry?.event('stats_recovered', {
        details: {
          quality: summary.quality,
          rtt_ms: summary.rttMs,
          interval_loss_percent: summary.intervalLossPercent,
          bitrate_in_bps: summary.bitrateInBps,
          bitrate_out_bps: summary.bitrateOutBps,
        },
        selectedCandidatePairId: summary.candidatePairId,
        statsIntervalMs: summary.statsIntervalMs,
        restartAttempt: this.restartAttempt,
        restartAttemptId: this.restartAttemptId,
      });
    }
    this.previousDegraded = degraded;

    const now = Date.now();
    if (now - this.lastStatsTelemetryAt >= this.config.statsTelemetryIntervalMs) {
      this.lastStatsTelemetryAt = now;
      this.telemetry?.event('stats_sample', {
        details: {
          rtt_ms: summary.rttMs,
          jitter_ms: summary.jitterMs,
          packets_lost: summary.packetsLost,
          packets_received: summary.packetsReceived,
          packets_lost_delta: summary.packetsLostDelta,
          packets_received_delta: summary.packetsReceivedDelta,
          loss_percent: summary.lossPercent,
          interval_loss_percent: summary.intervalLossPercent,
          bitrate_in_bps: summary.bitrateInBps,
          bitrate_out_bps: summary.bitrateOutBps,
          available_outgoing_bitrate_bps: summary.availableOutgoingBitrateBps,
          local_candidate_type: summary.localCandidateType,
          remote_candidate_type: summary.remoteCandidateType,
          inbound_ssrc: summary.inboundSsrc,
          outbound_ssrc: summary.outboundSsrc,
          delta_reset: summary.deltaReset,
        },
        selectedCandidatePairId: summary.candidatePairId,
        statsIntervalMs: summary.statsIntervalMs,
        restartAttempt: this.restartAttempt,
        restartAttemptId: this.restartAttemptId,
        priority: 'low',
      });
    }
  }

  /* ---------------- ICE recovery ---------------- */

  private handleConnectionState(state: RTCPeerConnectionState): void {
    const previous = this.currentConnectionState;
    this.currentConnectionState = state;
    this.options.onConnectionState(state);

    if (previous !== state) {
      this.telemetry?.event('ice_state_changed', {
        details: { from: previous, to: state },
        priority: 'normal',
        restartAttempt: this.restartAttempt,
        restartAttemptId: this.restartAttemptId,
      });
    }

    this.recovery.handleConnectionState(state);
  }

  private onIceRestartAttempt(info: IceAttemptInfo): void {
    this.restartAttempt = info.attempt;
    this.restartAttemptId = info.attemptId;
    this.telemetry?.event('ice_restart_attempt', {
      details: {
        reason: info.reason,
        max_attempts: info.maxAttempts,
        ice_connection_state: this.pc.iceConnectionState,
        connection_state: this.pc.connectionState,
      },
      restartAttempt: info.attempt,
      restartAttemptId: info.attemptId,
      statsIntervalMs: this.config.statsRestartIntervalMs,
    });
    // Новая попытка — чаще опрашиваем статистику, чтобы видеть восстановление.
    if (this.statsStarted) this.scheduleStatsPolling(0);
    this.negotiator.requestIceRestart(info.reason);
  }

  /**
   * Попытка ICE restart не удалась (таймаут или исключение restartIce).
   * Событие пишется на каждую неудачную попытку: по нему видно, была ли
   * следующая или восстановление исчерпано.
   */
  private onIceAttemptFailed(info: IceAttemptFailedInfo): void {
    this.telemetry?.event('ice_restart_failed', {
      details: {
        error: info.error,
        attempt: info.attempt,
        will_retry: info.willRetry,
        reason: info.reason,
        restart_reason: this.negotiator.getRestartReason(),
        connection_state: this.pc.connectionState,
        ice_connection_state: this.pc.iceConnectionState,
      },
      restartAttempt: info.attempt,
      restartAttemptId: info.attemptId,
      statsIntervalMs: this.config.statsRestartIntervalMs,
    });
  }

  private onIceRecoverySucceeded(info: IceRecoveredInfo): void {
    this.telemetry?.event('ice_restart_succeeded', {
      details: {
        attempts: info.attempts,
        downtime_ms: info.downtimeMs,
        reason: info.reason,
        connection_state: this.pc.connectionState,
      },
      restartAttempt: info.attempts,
      restartAttemptId: info.lastAttemptId,
    });
    this.restartAttempt = 0;
    this.restartAttemptId = null;
  }

  private onIceRecoveryExhausted(info: IceExhaustedInfo): void {
    this.telemetry?.event('ice_restart_exhausted', {
      details: {
        attempts: info.attempts,
        reason: info.reason,
        last_error: info.lastError,
        connection_state: this.pc.connectionState,
        ice_connection_state: this.pc.iceConnectionState,
      },
      restartAttempt: info.attempts,
      restartAttemptId: info.lastAttemptId,
    });

    const message = this.describeRecoveryFailure(info);
    this.options.onError(message);
    this.options.onConnectionRecoveryFailed?.(message);
    this.restartAttempt = 0;
    this.restartAttemptId = null;
  }

  private describeRecoveryFailure(info: IceExhaustedInfo): string {
    if (info.reason === 'ice_restart_disabled') {
      return 'Соединение потеряно: автоматический ICE restart выключен';
    }
    const attemptsText = info.attempts === 1 ? '1 попытка' : `${info.attempts} попытки`;
    const last = info.lastError ? ` (${info.lastError})` : '';
    return `Не удалось восстановить соединение: ${attemptsText} ICE restart${last}`;
  }

  /* ---------------- внутреннее ---------------- */

  private sendSignal(payload: NegotiationSignal): void {
    try {
      this.options.sendSignal(payload as SignalPayload);
    } catch (error) {
      this.options.onError(error instanceof Error ? error.message : 'Не удалось отправить сигнал');
    }
  }

  private ensureLocalStream(): MediaStream {
    if (!this.localStream) this.localStream = new MediaStream();
    return this.localStream;
  }

  /**
   * Почему недоступен захват медиа. Браузеры отдают navigator.mediaDevices
   * только в защищённом контексте: https, localhost или 127.0.0.1. По адресу
   * http://<локальный IP> (например, с телефона в той же сети) API отсутствует —
   * это самая частая причина «звонок не работает с мобильного».
   */
  private static describeMediaUnavailable(): string {
    if (typeof window !== 'undefined' && !window.isSecureContext) {
      return 'Звонки доступны только по HTTPS: по http://<адрес> браузер не даёт доступ к камере и микрофону. Откройте приложение по https (см. docs/DEPLOYMENT.md) или через localhost';
    }
    return 'Браузер не поддерживает захват медиа (getUserMedia). Если это встроенный браузер приложения, откройте ссылку в Chrome или Safari';
  }

  private async acquireMedia(withVideo: boolean): Promise<MediaStream> {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error(CallSession.describeMediaUnavailable());
    }

    const audio = await this.openAudio();

    if (!withVideo) {
      return new MediaStream(audio.getAudioTracks());
    }

    try {
      const video = await navigator.mediaDevices.getUserMedia({ video: true });
      const stream = new MediaStream();
      for (const track of audio.getTracks()) stream.addTrack(track);
      for (const track of video.getTracks()) stream.addTrack(track);
      return stream;
    } catch (error) {
      // Видеозвонок деградируем до аудио, если камеры нет или она запрещена.
      this.options.onError('Камера недоступна — продолжаем с аудио');
      void error;
      return new MediaStream(audio.getAudioTracks());
    }
  }

  /**
   * Открывает микрофон с учётом выбранного пользователем устройства.
   * Если сохранённое устройство исчезло (отключили гарнитуру), забываем выбор и
   * берём системный микрофон по умолчанию — звонок не должен падать из-за этого.
   */
  private async openAudio(micId: string | null = rememberedMicId()): Promise<MediaStream> {
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: audioTrackConstraints(micId) });
    } catch (error) {
      if (micId && shouldFallbackToDefaultMic(error)) {
        rememberMicId(null);
        this.options.onError('Выбранный микрофон недоступен — пробуем системный микрофон');
        return navigator.mediaDevices.getUserMedia({ audio: audioTrackConstraints(null) });
      }
      throw new Error(describeMediaError(error));
    }
  }

  /**
   * Переключает микрофон прямо во время звонка. Используется RTCRtpSender.
   * replaceTrack: он меняет дорожку без повторного согласования SDP, поэтому
   * собеседник не слышит разрыва.
   */
  async switchMicrophone(deviceId: string | null): Promise<void> {
    if (this.closed) return;

    const audio = await this.openAudio(deviceId);
    const [newTrack] = audio.getAudioTracks();
    if (!newTrack) throw new Error('Не удалось получить доступ к выбранному микрофону');

    const sender = this.pc.getSenders().find((item) => item.track?.kind === 'audio');
    if (sender) {
      await sender.replaceTrack(newTrack);
    } else {
      // Аудиодорожки ещё не было (например, звонок начали без микрофона) —
      // addTrack вызовет renegotiation, perfect negotiation её обработает.
      const stream = this.ensureLocalStream();
      stream.addTrack(newTrack);
      this.pc.addTrack(newTrack, stream);
      this.options.onLocalStream(stream);
      rememberMicId(deviceId);
      return;
    }

    const stream = this.ensureLocalStream();
    for (const oldTrack of stream.getAudioTracks()) {
      stream.removeTrack(oldTrack);
      oldTrack.stop();
    }
    stream.addTrack(newTrack);
    rememberMicId(deviceId);

    this.options.onLocalStream(stream);
  }

  private async attachPreparedTracks(): Promise<void> {
    const stream = this.localStream;
    if (!stream) return;
    for (const track of stream.getTracks()) {
      const alreadyAdded = this.pc.getSenders().some((sender) => sender.track === track);
      if (alreadyAdded) continue;
      this.pc.addTrack(track, stream);
    }
  }
}
