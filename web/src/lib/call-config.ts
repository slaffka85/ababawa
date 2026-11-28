/**
 * Настройки жизненного цикла звонка: частота сбора статистики, параметры
 * восстановления ICE и телеметрии.
 *
 * Значения по умолчанию рассчитаны на 1:1 аудио/видеозвонок. Любое значение
 * можно переопределить переменной окружения Vite (VITE_CALL_*), а в тестах —
 * передать объект напрямую в CallSession/StatsAggregator/IceRecoveryController.
 */

export interface CallRuntimeConfig {
  /** Обычная частота опроса getStats. */
  statsIntervalMs: number;
  /** Более частый опрос при деградации (плохое качество, потери, высокий RTT). */
  statsDegradedIntervalMs: number;
  /** Самый частый опрос во время ICE restart/renegotiation. */
  statsRestartIntervalMs: number;
  /** Как часто отправлять накопленный stats_sample в телеметрию. */
  statsTelemetryIntervalMs: number;

  /** Сколько ждём самостоятельного восстановления ICE при 'disconnected'. */
  disconnectedGraceMs: number;
  /** Сколько ждём результат одной попытки ICE restart, прежде чем повторить. */
  iceRestartAttemptTimeoutMs: number;
  /** Максимум попыток ICE restart на один инцидент (0 — не перезапускать). */
  iceRestartMaxAttempts: number;
  /** База экспоненциальной задержки между попытками. */
  iceRestartBackoffBaseMs: number;
  /** Потолок экспоненциальной задержки между попытками. */
  iceRestartBackoffMaxMs: number;

  /** Размер одного batch телеметрии. */
  telemetryBatchSize: number;
  /** Период отправки накопленных событий. */
  telemetryFlushIntervalMs: number;
  /** Верхняя граница буфера клиентских событий (backpressure). */
  telemetryMaxBuffer: number;
  /** Сколько раз повторять отправку batch при ошибке. */
  telemetryMaxRetries: number;
  /** База экспоненциальной задержки retry телеметрии. */
  telemetryRetryBaseMs: number;
  /** Потолок задержки retry телеметрии. */
  telemetryRetryMaxMs: number;
}

export const DEFAULT_CALL_CONFIG: Readonly<CallRuntimeConfig> = Object.freeze({
  statsIntervalMs: 2_000,
  statsDegradedIntervalMs: 1_000,
  statsRestartIntervalMs: 500,
  statsTelemetryIntervalMs: 5_000,

  disconnectedGraceMs: 5_000,
  iceRestartAttemptTimeoutMs: 15_000,
  iceRestartMaxAttempts: 3,
  iceRestartBackoffBaseMs: 2_000,
  iceRestartBackoffMaxMs: 15_000,

  telemetryBatchSize: 20,
  telemetryFlushIntervalMs: 5_000,
  telemetryMaxBuffer: 200,
  telemetryMaxRetries: 3,
  telemetryRetryBaseMs: 1_000,
  telemetryRetryMaxMs: 10_000,
});

/**
 * Значение VITE_*-переменной. Под Vite `import.meta.env` подставляется сборщиком;
 * в Node (юнит-тесты) его нет — читаем защищённо и падаем на дефолты.
 */
function envRaw(name: string): string | undefined {
  const env = import.meta.env as unknown as Record<string, string | undefined> | undefined;
  return env ? env[name] : undefined;
}

function envNumber(name: string, fallback: number): number {
  const raw = envRaw(name);
  if (raw === undefined || raw.trim() === '') return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

/** Конфиг из VITE_CALL_* с валидацией диапазонов. */
export function loadCallConfig(overrides: Partial<CallRuntimeConfig> = {}): CallRuntimeConfig {
  const config: CallRuntimeConfig = {
    statsIntervalMs: envNumber('VITE_CALL_STATS_INTERVAL_MS', DEFAULT_CALL_CONFIG.statsIntervalMs),
    statsDegradedIntervalMs: envNumber(
      'VITE_CALL_STATS_DEGRADED_INTERVAL_MS',
      DEFAULT_CALL_CONFIG.statsDegradedIntervalMs,
    ),
    statsRestartIntervalMs: envNumber(
      'VITE_CALL_STATS_RESTART_INTERVAL_MS',
      DEFAULT_CALL_CONFIG.statsRestartIntervalMs,
    ),
    statsTelemetryIntervalMs: envNumber(
      'VITE_CALL_STATS_TELEMETRY_INTERVAL_MS',
      DEFAULT_CALL_CONFIG.statsTelemetryIntervalMs,
    ),
    disconnectedGraceMs: envNumber(
      'VITE_CALL_DISCONNECTED_GRACE_MS',
      DEFAULT_CALL_CONFIG.disconnectedGraceMs,
    ),
    iceRestartAttemptTimeoutMs: envNumber(
      'VITE_CALL_ICE_RESTART_ATTEMPT_TIMEOUT_MS',
      DEFAULT_CALL_CONFIG.iceRestartAttemptTimeoutMs,
    ),
    iceRestartMaxAttempts: envNumber(
      'VITE_CALL_ICE_RESTART_MAX_ATTEMPTS',
      DEFAULT_CALL_CONFIG.iceRestartMaxAttempts,
    ),
    iceRestartBackoffBaseMs: envNumber(
      'VITE_CALL_ICE_RESTART_BACKOFF_BASE_MS',
      DEFAULT_CALL_CONFIG.iceRestartBackoffBaseMs,
    ),
    iceRestartBackoffMaxMs: envNumber(
      'VITE_CALL_ICE_RESTART_BACKOFF_MAX_MS',
      DEFAULT_CALL_CONFIG.iceRestartBackoffMaxMs,
    ),
    telemetryBatchSize: envNumber(
      'VITE_CALL_TELEMETRY_BATCH_SIZE',
      DEFAULT_CALL_CONFIG.telemetryBatchSize,
    ),
    telemetryFlushIntervalMs: envNumber(
      'VITE_CALL_TELEMETRY_FLUSH_INTERVAL_MS',
      DEFAULT_CALL_CONFIG.telemetryFlushIntervalMs,
    ),
    telemetryMaxBuffer: envNumber(
      'VITE_CALL_TELEMETRY_MAX_BUFFER',
      DEFAULT_CALL_CONFIG.telemetryMaxBuffer,
    ),
    telemetryMaxRetries: envNumber(
      'VITE_CALL_TELEMETRY_MAX_RETRIES',
      DEFAULT_CALL_CONFIG.telemetryMaxRetries,
    ),
    telemetryRetryBaseMs: envNumber(
      'VITE_CALL_TELEMETRY_RETRY_BASE_MS',
      DEFAULT_CALL_CONFIG.telemetryRetryBaseMs,
    ),
    telemetryRetryMaxMs: envNumber(
      'VITE_CALL_TELEMETRY_RETRY_MAX_MS',
      DEFAULT_CALL_CONFIG.telemetryRetryMaxMs,
    ),
  };
  return { ...config, ...overrides };
}

/** Минимальные допустимые значения, чтобы случайная правка env не сломала звонок. */
export function normalizeCallConfig(config: CallRuntimeConfig): CallRuntimeConfig {
  const atLeast = (value: number, min: number): number => Math.max(min, Math.round(value));
  return {
    ...config,
    statsIntervalMs: atLeast(config.statsIntervalMs, 250),
    statsDegradedIntervalMs: atLeast(config.statsDegradedIntervalMs, 250),
    statsRestartIntervalMs: atLeast(config.statsRestartIntervalMs, 250),
    statsTelemetryIntervalMs: atLeast(config.statsTelemetryIntervalMs, 1_000),
    disconnectedGraceMs: atLeast(config.disconnectedGraceMs, 0),
    iceRestartAttemptTimeoutMs: atLeast(config.iceRestartAttemptTimeoutMs, 1_000),
    iceRestartMaxAttempts: atLeast(config.iceRestartMaxAttempts, 0),
    iceRestartBackoffBaseMs: atLeast(config.iceRestartBackoffBaseMs, 0),
    iceRestartBackoffMaxMs: Math.max(
      atLeast(config.iceRestartBackoffMaxMs, 0),
      atLeast(config.iceRestartBackoffBaseMs, 0),
    ),
    telemetryBatchSize: atLeast(config.telemetryBatchSize, 1),
    telemetryFlushIntervalMs: atLeast(config.telemetryFlushIntervalMs, 1_000),
    telemetryMaxBuffer: atLeast(config.telemetryMaxBuffer, 1),
    telemetryMaxRetries: atLeast(config.telemetryMaxRetries, 0),
    telemetryRetryBaseMs: atLeast(config.telemetryRetryBaseMs, 100),
    telemetryRetryMaxMs: Math.max(
      atLeast(config.telemetryRetryMaxMs, 100),
      atLeast(config.telemetryRetryBaseMs, 100),
    ),
  };
}
