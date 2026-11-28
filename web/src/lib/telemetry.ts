/**
 * Клиентская телеметрия звонка.
 *
 * События копятся в ограниченном буфере и уходят batch'ами на backend
 * (`POST /api/v1/calls/telemetry`). Буфер ограничен, при переполнении первыми
 * выбрасываются низкоприоритетные события (stats_sample), «критические»
 * (call_ended/session_ended) держатся до последнего. Отправка строго
 * последовательная: параллельные flush не допускаются, ошибка даёт retry с
 * экспоненциальной задержкой, после исчерпания retry batch отбрасывается.
 *
 * Модуль не импортирует api/React: транспорт и таймеры внедряются. Это
 * позволяет тестировать backpressure и retry без сети.
 */

export const TELEMETRY_SCHEMA_VERSION = 1 as const;

export type CallTelemetryEventName =
  | 'call_started'
  | 'call_ended'
  | 'session_started'
  | 'session_ended'
  | 'ice_state_changed'
  | 'ice_restart_requested'
  | 'ice_restart_attempt'
  | 'ice_restart_succeeded'
  | 'ice_restart_failed'
  | 'ice_restart_exhausted'
  | 'offer_collision'
  | 'candidate_ignored'
  | 'candidate_pair_changed'
  | 'stats_sample'
  | 'stats_degraded'
  | 'stats_recovered'
  | 'telemetry_dropped';

export type PeerRole = 'caller' | 'callee';
export type TelemetryPriority = 'critical' | 'normal' | 'low';

export interface CallTelemetryEvent {
  schemaVersion: typeof TELEMETRY_SCHEMA_VERSION;
  eventId: string;
  event: CallTelemetryEventName;
  /** Время клиента в ISO-8601 (UTC). */
  timestamp: string;
  callId: string;
  sessionId: string;
  /** Номер попытки ICE restart в текущем инциденте (0 — не restart). */
  restartAttempt: number;
  restartAttemptId: string | null;
  peerRole: PeerRole;
  browserName: string | null;
  browserVersion: string | null;
  clientPlatform: string | null;
  networkType: string | null;
  selectedCandidatePairId: string | null;
  statsIntervalMs: number | null;
  /** Всегда JSON-объект, не строка. */
  details: Record<string, unknown>;
}

export interface BrowserContext {
  browserName: string | null;
  browserVersion: string | null;
  clientPlatform: string | null;
  networkType: string | null;
}

export interface TelemetryContext extends BrowserContext {
  callId: string;
  sessionId: string;
  peerRole: PeerRole;
  selectedCandidatePairId: string | null;
  statsIntervalMs: number | null;
}

export interface TelemetryConfig {
  batchSize: number;
  flushIntervalMs: number;
  maxBuffer: number;
  maxRetries: number;
  retryBaseMs: number;
  retryMaxMs: number;
}

export interface TelemetryTimers {
  setTimeout(callback: () => void, delayMs: number): number;
  clearTimeout(handle: number): void;
}

export type TelemetryTransport = (events: CallTelemetryEvent[]) => Promise<void>;

export interface TelemetryDeps {
  timers?: TelemetryTimers;
  createId?: () => string;
  now?: () => number;
}

export interface TelemetryEventInput {
  restartAttempt?: number;
  restartAttemptId?: string | null;
  selectedCandidatePairId?: string | null;
  statsIntervalMs?: number | null;
  details?: Record<string, unknown>;
  priority?: TelemetryPriority;
  /** Явный sessionId (session_ended должен ссылаться на закрытую сессию). */
  sessionId?: string;
}

interface QueuedTelemetry {
  event: CallTelemetryEvent;
  priority: TelemetryPriority;
}

const browserTimers: TelemetryTimers = {
  setTimeout: (callback, delayMs) => window.setTimeout(callback, delayMs),
  clearTimeout: (handle) => window.clearTimeout(handle),
};

function defaultId(): string {
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') return cryptoObj.randomUUID();
  return `evt-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

const PRIORITY_ORDER: Record<TelemetryPriority, number> = { critical: 0, normal: 1, low: 2 };

/** Network Information API есть не во всех браузерах — читаем максимально осторожно. */
export interface NetworkInformationLike {
  effectiveType?: string;
  type?: string;
}

function currentConnection(): NetworkInformationLike | null {
  if (typeof navigator === 'undefined') return null;
  const candidate = (navigator as Navigator & { connection?: NetworkInformationLike }).connection;
  return candidate ?? null;
}

/** Best-effort определение браузера/платформы/типа сети без отправки User-Agent. */
export function detectBrowserContext(
  userAgent: string | null = typeof navigator === 'undefined' ? null : navigator.userAgent,
  platform: string | null = typeof navigator === 'undefined' ? null : navigator.platform,
  connection: { effectiveType?: string; type?: string } | null = null,
): BrowserContext {
  const ua = userAgent ?? '';
  let browserName: string | null = null;
  let browserVersion: string | null = null;

  const matchers: Array<{ name: string; re: RegExp }> = [
    { name: 'Edge', re: /Edg\/([\d.]+)/ },
    { name: 'Opera', re: /OPR\/([\d.]+)/ },
    { name: 'Firefox', re: /Firefox\/([\d.]+)/ },
    { name: 'Chrome', re: /(?:Chrome|CriOS)\/([\d.]+)/ },
    { name: 'Safari', re: /Version\/([\d.]+).*Safari/ },
  ];
  for (const matcher of matchers) {
    const match = matcher.re.exec(ua);
    if (match) {
      browserName = matcher.name;
      browserVersion = match[1];
      break;
    }
  }

  return {
    browserName,
    browserVersion,
    clientPlatform: platform && platform.length > 0 ? platform : null,
    networkType: connection?.effectiveType ?? connection?.type ?? null,
  };
}

/**
 * Ограниченная очередь с одним in-flight batch'ем. Публичный API намеренно
 * синхронный: main thread звонка не ждёт сеть.
 */
export class CallTelemetry {
  private context: TelemetryContext;
  private readonly transport: TelemetryTransport;
  private readonly config: TelemetryConfig;
  private readonly timers: TelemetryTimers;
  private readonly createId: () => string;
  private readonly now: () => number;

  private queue: QueuedTelemetry[] = [];
  private timer: number | null = null;
  private retryTimer: number | null = null;
  private inFlight: Promise<void> | null = null;
  private closed = false;
  private started = false;
  private retryAttempt = 0;
  private dropped = 0;

  constructor(
    context: TelemetryContext,
    transport: TelemetryTransport,
    config: TelemetryConfig,
    deps: TelemetryDeps = {},
  ) {
    this.context = context;
    this.transport = transport;
    this.config = config;
    this.timers = deps.timers ?? browserTimers;
    this.createId = deps.createId ?? defaultId;
    this.now = deps.now ?? (() => Date.now());
  }

  getContext(): Readonly<TelemetryContext> {
    return this.context;
  }

  getDroppedCount(): number {
    return this.dropped;
  }

  getQueueSize(): number {
    return this.queue.length;
  }

  /** Начать периодическую отправку. Повторный вызов безопасен. */
  start(): void {
    if (this.started || this.closed) return;
    this.started = true;
    this.scheduleFlush();
  }

  /** Новая WebRTC-сессия в рамках того же логического звонка. */
  beginSession(sessionId: string): void {
    this.context = { ...this.context, sessionId };
  }

  updateNetworkContext(partial: Partial<BrowserContext>): void {
    this.context = { ...this.context, ...partial };
  }

  updateSelectedCandidatePair(candidatePairId: string | null): void {
    this.context = { ...this.context, selectedCandidatePairId: candidatePairId };
  }

  updateStatsInterval(statsIntervalMs: number | null): void {
    this.context = { ...this.context, statsIntervalMs };
  }

  /** Поставить событие в очередь. После close() события игнорируются. */
  event(name: CallTelemetryEventName, input: TelemetryEventInput = {}): void {
    if (this.closed) return;
    const priority = input.priority ?? defaultPriority(name);
    const event: CallTelemetryEvent = {
      schemaVersion: TELEMETRY_SCHEMA_VERSION,
      eventId: this.createId(),
      event: name,
      timestamp: new Date(this.now()).toISOString(),
      callId: this.context.callId,
      sessionId: input.sessionId ?? this.context.sessionId,
      restartAttempt: input.restartAttempt ?? 0,
      restartAttemptId: input.restartAttemptId ?? null,
      peerRole: this.context.peerRole,
      browserName: this.context.browserName,
      browserVersion: this.context.browserVersion,
      clientPlatform: this.context.clientPlatform,
      networkType: this.context.networkType,
      selectedCandidatePairId:
        input.selectedCandidatePairId ?? this.context.selectedCandidatePairId,
      statsIntervalMs: input.statsIntervalMs ?? this.context.statsIntervalMs,
      details: input.details && typeof input.details === 'object' && !Array.isArray(input.details)
        ? input.details
        : {},
    };

    this.enqueue({ event, priority });
    if (this.queue.length >= this.config.batchSize) this.scheduleFlush(0);
  }

  /** Немедленная отправка накопленного (не блокирует вызывающего). */
  flushNow(): void {
    if (this.closed) return;
    this.scheduleFlush(0);
  }

  /**
   * Прекратить приём событий и отправить остаток. Возвращает promise, чтобы
   * тест/вызывающий код мог дождаться финального batch'а, но store вызывает
   * его без await — звонок не должен ждать сеть.
   */
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.stopTimer();
    this.stopRetryTimer();
    if (this.inFlight) {
      try {
        await this.inFlight;
      } catch {
        // ошибка уже обработана внутри flush
      }
    }
    // Финальный flush: одна попытка, без бесконечного retry после hangup.
    if (this.queue.length > 0) {
      const batch = this.drainBatch();
      try {
        await this.transport(batch.map((item) => item.event));
      } catch {
        this.dropped += batch.length;
      }
      this.queue = [];
    }
  }

  /* ------------------------------------------------------------------ */

  private enqueue(item: QueuedTelemetry): void {
    this.queue.push(item);
    this.enforceBufferLimit();
  }

  private enforceBufferLimit(): void {
    while (this.queue.length > this.config.maxBuffer) {
      const index = this.lowestPriorityIndex();
      if (index < 0) break;
      this.queue.splice(index, 1);
      this.dropped += 1;
    }
  }

  private lowestPriorityIndex(): number {
    let index = -1;
    let priority = -1;
    for (let i = 0; i < this.queue.length; i += 1) {
      const value = PRIORITY_ORDER[this.queue[i].priority];
      if (value > priority) {
        priority = value;
        index = i;
      }
    }
    return index;
  }

  private drainBatch(): QueuedTelemetry[] {
    return this.queue.splice(0, Math.max(1, this.config.batchSize));
  }

  private scheduleFlush(delayMs = this.config.flushIntervalMs): void {
    if (this.closed) return;
    if (this.timer !== null) {
      if (delayMs > 0) return;
      this.stopTimer();
    }
    this.timer = this.timers.setTimeout(() => {
      this.timer = null;
      void this.flush();
    }, Math.max(0, delayMs));
  }

  private async flush(): Promise<void> {
    if (!this.started || this.closed) return;
    if (this.inFlight) return;
    if (this.queue.length === 0) {
      this.scheduleFlush();
      return;
    }

    const run = this.flushBatch();
    this.inFlight = run;
    try {
      await run;
    } finally {
      this.inFlight = null;
    }
    if (!this.closed) {
      if (this.queue.length >= this.config.batchSize) this.scheduleFlush(0);
      else this.scheduleFlush();
    }
  }

  private async flushBatch(): Promise<void> {
    const batch = this.drainBatch();
    try {
      await this.transport(batch.map((item) => item.event));
      this.retryAttempt = 0;
    } catch {
      if (this.closed) {
        this.dropped += batch.length;
        return;
      }
      if (this.retryAttempt >= this.config.maxRetries) {
        // Batch отброшен: иначе очередь будет вечно пытаться отправить его
        // и блокировать более свежие события.
        this.dropped += batch.length;
        this.retryAttempt = 0;
        return;
      }
      this.retryAttempt += 1;
      // Возвращаем batch в начало очереди, чтобы сохранить порядок событий.
      this.queue.unshift(...batch);
      const delay = Math.min(
        this.config.retryMaxMs,
        this.config.retryBaseMs * 2 ** (this.retryAttempt - 1),
      );
      this.stopRetryTimer();
      this.retryTimer = this.timers.setTimeout(() => {
        this.retryTimer = null;
        void this.flush();
      }, delay);
    }
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      this.timers.clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private stopRetryTimer(): void {
    if (this.retryTimer !== null) {
      this.timers.clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }
}

function defaultPriority(name: CallTelemetryEventName): TelemetryPriority {
  switch (name) {
    case 'call_started':
    case 'call_ended':
    case 'session_started':
    case 'session_ended':
    case 'ice_restart_failed':
    case 'ice_restart_exhausted':
      return 'critical';
    case 'stats_sample':
    case 'candidate_ignored':
    case 'telemetry_dropped':
      return 'low';
    default:
      return 'normal';
  }
}

/** Удобная фабрика: контекст браузера + контекст звонка. */
export function createCallTelemetry(input: {
  callId: string;
  sessionId: string;
  peerRole: PeerRole;
  transport: TelemetryTransport;
  config: TelemetryConfig;
  browser?: BrowserContext;
  deps?: TelemetryDeps;
}): CallTelemetry {
  // networkType доступен только там, где есть Network Information API;
  // в остальных браузерах поле остаётся null (не выдумываем значение).
  const browser = input.browser ?? detectBrowserContext(undefined, undefined, currentConnection());
  const context: TelemetryContext = {
    callId: input.callId,
    sessionId: input.sessionId,
    peerRole: input.peerRole,
    selectedCandidatePairId: null,
    statsIntervalMs: null,
    ...browser,
  };
  return new CallTelemetry(context, input.transport, input.config, input.deps);
}
