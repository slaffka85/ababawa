/**
 * Восстановление ICE: таймер disconnected, повторные ICE restart с
 * экспоненциальным backoff, отмена при восстановлении и понятная причина
 * окончательной неудачи.
 *
 * Класс не знает про RTCPeerConnection: снаружи приходят состояния соединения,
 * а наружу уходят команды «сделай попытку restartIce». Это позволяет
 * протестировать сценарии (оба peer'а одновременно, временный disconnected,
 * исчерпание попыток, завершение звонка во время restart) без браузера.
 */

export type IceRecoveryPhase = 'idle' | 'waiting' | 'backoff' | 'restarting' | 'exhausted' | 'closed';

export interface IceRecoveryConfig {
  /** Ожидание самостоятельного восстановления после 'disconnected'. */
  disconnectedGraceMs: number;
  /** Таймаут одной попытки restartIce. */
  attemptTimeoutMs: number;
  /** Максимум попыток на инцидент. 0 — ICE restart выключен. */
  maxAttempts: number;
  /** База экспоненциальной задержки между попытками. */
  backoffBaseMs: number;
  /** Потолок задержки. */
  backoffMaxMs: number;
}

export interface IceRecoveryTimers {
  setTimeout(callback: () => void, delayMs: number): number;
  clearTimeout(handle: number): void;
  now(): number;
}

export interface IceAttemptInfo {
  attempt: number;
  attemptId: string;
  maxAttempts: number;
  reason: string;
}

export interface IceRecoveredInfo {
  attempts: number;
  lastAttemptId: string | null;
  downtimeMs: number;
  reason: string | null;
}

export interface IceExhaustedInfo {
  attempts: number;
  lastAttemptId: string | null;
  reason: string;
  lastError: string | null;
}

export interface IceAttemptFailedInfo {
  attempt: number;
  attemptId: string;
  /** Причина инцидента, с которой запрашивался restart. */
  reason: string | null;
  /** Конкретная ошибка попытки (restart_timeout, исключение restartIce, …). */
  error: string | null;
  /** true — будет ещё одна попытка; false — дальше только onExhausted. */
  willRetry: boolean;
}

export interface IceRecoveryCallbacks {
  /** Пора выполнить restartIce для указанной попытки. */
  onAttempt: (info: IceAttemptInfo) => void;
  /** Попытка restartIce не удалась (таймаут или исключение). */
  onAttemptFailed?: (info: IceAttemptFailedInfo) => void;
  /** Соединение восстановилось (или ручной restart признан успешным). */
  onRecovered: (info: IceRecoveredInfo) => void;
  /** Все попытки исчерпаны; соединение не восстановилось. */
  onExhausted: (info: IceExhaustedInfo) => void;
  /** Диагностика смены фазы (для телеметрии/логов). */
  onPhaseChange?: (phase: IceRecoveryPhase, detail: { attempt: number; reason: string | null }) => void;
}

export interface IceRecoveryDeps {
  timers?: IceRecoveryTimers;
  createId?: () => string;
  /** Текущее состояние PC — нужно, чтобы отличить успех ручного restart от зависания. */
  isConnected?: () => boolean;
}

const browserTimers: IceRecoveryTimers = {
  setTimeout: (callback, delayMs) => window.setTimeout(callback, delayMs),
  clearTimeout: (handle) => window.clearTimeout(handle),
  now: () => Date.now(),
};

function defaultId(): string {
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') return cryptoObj.randomUUID();
  return `restart-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export class IceRecoveryController {
  private readonly config: IceRecoveryConfig;
  private readonly callbacks: IceRecoveryCallbacks;
  private readonly timers: IceRecoveryTimers;
  private readonly createId: () => string;
  private readonly isConnected: () => boolean;

  private phase: IceRecoveryPhase = 'idle';
  private attempt = 0;
  private attemptId: string | null = null;
  private reason: string | null = null;
  private lastError: string | null = null;
  private firstFailureAt: number | null = null;
  private attemptStartedAt: number | null = null;

  private graceTimer: number | null = null;
  private attemptTimer: number | null = null;
  private backoffTimer: number | null = null;

  constructor(
    config: IceRecoveryConfig,
    callbacks: IceRecoveryCallbacks,
    deps: IceRecoveryDeps = {},
  ) {
    this.config = config;
    this.callbacks = callbacks;
    this.timers = deps.timers ?? browserTimers;
    this.createId = deps.createId ?? defaultId;
    this.isConnected = deps.isConnected ?? (() => false);
  }

  getPhase(): IceRecoveryPhase {
    return this.phase;
  }

  getAttempt(): number {
    return this.attempt;
  }

  getAttemptId(): string | null {
    return this.attemptId;
  }

  isRestarting(): boolean {
    return this.phase === 'restarting' || this.phase === 'backoff';
  }

  /** PC сообщил новое состояние. */
  handleConnectionState(state: RTCPeerConnectionState): void {
    if (this.phase === 'closed') return;
    if (state === 'connected') {
      this.markConnected();
      return;
    }
    if (state === 'failed') {
      this.firstFailureAt ??= this.timers.now();
      this.cancelTimers();
      this.startAttempt('connection_failed');
      return;
    }
    if (state === 'disconnected') {
      this.firstFailureAt ??= this.timers.now();
      if (this.phase === 'idle') this.scheduleGrace();
      return;
    }
    if (state === 'closed') {
      this.cancel('connection_closed');
    }
  }

  /**
   * Ручной ICE restart (например, после реконнекта WS). Всегда начинает новый
   * инцидент: счётчик попыток сбрасывается, параллельная попытка не создаётся.
   */
  requestRestart(reason: string): void {
    if (this.phase === 'closed' || this.phase === 'exhausted') return;
    if (this.phase === 'restarting') return;
    this.cancelTimers();
    this.attempt = 0;
    this.attemptId = null;
    this.lastError = null;
    this.firstFailureAt ??= this.timers.now();
    this.startAttempt(reason);
  }

  /** restartIce() бросил исключение — не ждём таймаут, сразу решаем судьбу попытки. */
  attemptFailed(error: string): void {
    if (this.phase === 'closed' || this.phase === 'exhausted') return;
    if (this.phase !== 'restarting') return;
    this.lastError = error;
    this.clearAttemptTimer();
    this.advanceOrExhaust();
  }

  /**
   * Полное прекращение: звонок завершён. Отменяет все таймеры, включая
   * запланированный backoff; callbacks больше не вызываются.
   */
  cancel(reason = 'call_ended'): void {
    if (this.phase === 'closed') return;
    this.cancelTimers();
    this.phase = 'closed';
    this.callbacks.onPhaseChange?.('closed', { attempt: this.attempt, reason });
  }

  private scheduleGrace(): void {
    this.setPhase('waiting', this.reason);
    this.clearGraceTimer();
    this.graceTimer = this.timers.setTimeout(() => {
      this.graceTimer = null;
      if (this.phase !== 'waiting') return;
      if (this.isConnected()) {
        this.markConnected();
        return;
      }
      this.startAttempt('disconnected_timeout');
    }, this.config.disconnectedGraceMs);
  }

  private startAttempt(reason: string): void {
    if (this.phase === 'closed' || this.phase === 'exhausted') return;
    // Параллельные restart не допускаются: текущая попытка должна завершиться.
    if (this.phase === 'restarting') return;

    this.clearBackoffTimer();
    this.clearGraceTimer();
    this.clearAttemptTimer();

    if (this.config.maxAttempts <= 0) {
      this.reason = reason;
      this.phase = 'exhausted';
      this.callbacks.onExhausted({
        attempts: 0,
        lastAttemptId: null,
        reason: 'ice_restart_disabled',
        lastError: null,
      });
      return;
    }

    this.attempt += 1;
    this.attemptId = this.createId();
    this.reason = reason;
    this.attemptStartedAt = this.timers.now();
    this.setPhase('restarting', reason);
    this.callbacks.onAttempt({
      attempt: this.attempt,
      attemptId: this.attemptId,
      maxAttempts: this.config.maxAttempts,
      reason,
    });

    this.attemptTimer = this.timers.setTimeout(() => {
      this.attemptTimer = null;
      if (this.phase !== 'restarting') return;
      if (this.isConnected()) {
        // Ручной restart поверх рабочего соединения: к моменту таймаута
        // соединение по-прежнему connected — считаем попытку успешной.
        this.markConnected();
        return;
      }
      this.lastError = 'restart_timeout';
      this.advanceOrExhaust();
    }, this.config.attemptTimeoutMs);
  }

  private markConnected(): void {
    if (this.phase === 'idle') return;
    if (this.phase === 'closed' || this.phase === 'exhausted') return;

    const attempts = this.attempt;
    const lastAttemptId = this.attemptId;
    const reason = this.reason;
    const started = this.firstFailureAt ?? this.attemptStartedAt ?? this.timers.now();

    this.cancelTimers();
    this.phase = 'idle';
    this.attempt = 0;
    this.attemptId = null;
    this.reason = null;
    this.lastError = null;
    this.firstFailureAt = null;
    this.attemptStartedAt = null;

    if (attempts === 0) {
      // 'disconnected' прошёл сам, до первой попытки restart: это не ICE restart,
      // поэтому onRecovered не шлём — иначе телеметрия покажет «0 попыток».
      return;
    }

    this.callbacks.onRecovered({
      attempts,
      lastAttemptId,
      downtimeMs: Math.max(0, this.timers.now() - started),
      reason,
    });
  }

  private advanceOrExhaust(): void {
    const willRetry = this.attempt < this.config.maxAttempts;
    const attemptId = this.attemptId;
    if (attemptId !== null) {
      this.callbacks.onAttemptFailed?.({
        attempt: this.attempt,
        attemptId,
        reason: this.reason,
        error: this.lastError,
        willRetry,
      });
    }
    if (!willRetry) {
      this.exhaust();
      return;
    }
    const delay = this.backoffDelay(this.attempt);
    const reason = this.reason ?? 'ice_restart_retry';
    this.setPhase('backoff', reason);
    this.backoffTimer = this.timers.setTimeout(() => {
      this.backoffTimer = null;
      if (this.phase !== 'backoff') return;
      this.startAttempt(reason);
    }, delay);
  }

  private exhaust(): void {
    this.clearAttemptTimer();
    this.clearBackoffTimer();
    this.clearGraceTimer();
    this.phase = 'exhausted';
    this.callbacks.onExhausted({
      attempts: this.attempt,
      lastAttemptId: this.attemptId,
      reason: this.reason ?? 'ice_restart_exhausted',
      lastError: this.lastError,
    });
  }

  /** Экспоненциальный backoff: base, 2*base, 4*base… не выше max. */
  private backoffDelay(attempt: number): number {
    const exponential = this.config.backoffBaseMs * 2 ** Math.max(0, attempt - 1);
    return Math.max(0, Math.min(this.config.backoffMaxMs, exponential));
  }

  private setPhase(phase: IceRecoveryPhase, reason: string | null): void {
    this.phase = phase;
    this.callbacks.onPhaseChange?.(phase, { attempt: this.attempt, reason });
  }

  private cancelTimers(): void {
    this.clearGraceTimer();
    this.clearAttemptTimer();
    this.clearBackoffTimer();
  }

  private clearGraceTimer(): void {
    if (this.graceTimer !== null) {
      this.timers.clearTimeout(this.graceTimer);
      this.graceTimer = null;
    }
  }

  private clearAttemptTimer(): void {
    if (this.attemptTimer !== null) {
      this.timers.clearTimeout(this.attemptTimer);
      this.attemptTimer = null;
    }
  }

  private clearBackoffTimer(): void {
    if (this.backoffTimer !== null) {
      this.timers.clearTimeout(this.backoffTimer);
      this.backoffTimer = null;
    }
  }
}

export interface FakeTimers extends IceRecoveryTimers {
  /** Продвинуть виртуальное время, выполнив все таймеры в порядке срабатывания. */
  advance(ms: number): void;
  pending(): number;
}

/**
 * Детерминированные таймеры для тестов: без sleep и без зависимости от
 * реального времени. Живут в этом модуле, чтобы тесты не тащили fake-timers.
 */
export function createFakeTimers(startAt = 1_000_000): FakeTimers {
  interface Scheduled {
    handle: number;
    at: number;
    callback: () => void;
  }
  let current = startAt;
  let nextHandle = 1;
  const scheduled = new Map<number, Scheduled>();

  function now(): number {
    return current;
  }

  function setTimeout(callback: () => void, delayMs: number): number {
    const handle = nextHandle;
    nextHandle += 1;
    scheduled.set(handle, { handle, at: current + Math.max(0, delayMs), callback });
    return handle;
  }

  function clearTimeout(handle: number): void {
    scheduled.delete(handle);
  }

  function advance(ms: number): void {
    const target = current + Math.max(0, ms);
    for (;;) {
      let next: Scheduled | null = null;
      for (const item of scheduled.values()) {
        if (item.at > target) continue;
        if (!next || item.at < next.at || (item.at === next.at && item.handle < next.handle)) {
          next = item;
        }
      }
      if (!next) break;
      scheduled.delete(next.handle);
      current = next.at;
      next.callback();
    }
    current = target;
  }

  return {
    setTimeout,
    clearTimeout,
    now,
    advance,
    pending: () => scheduled.size,
  };
}
