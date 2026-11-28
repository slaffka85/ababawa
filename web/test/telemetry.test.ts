/**
 * Клиентская телеметрия: структура события, batch/retry/backpressure,
 * ограниченный буфер, корректное закрытие и details строго как JSON-объект.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CallTelemetry, TELEMETRY_SCHEMA_VERSION, createCallTelemetry, detectBrowserContext } from '../src/lib/telemetry.ts';
import type { CallTelemetryEvent, TelemetryConfig } from '../src/lib/telemetry.ts';
import { createFakeTimers } from '../src/lib/ice-recovery.ts';

const BASE_CONFIG: TelemetryConfig = {
  batchSize: 2,
  flushIntervalMs: 5_000,
  maxBuffer: 10,
  maxRetries: 2,
  retryBaseMs: 100,
  retryMaxMs: 1_000,
};

/** Дать отработать промисам транспорта (flush асинхронный, таймеры — нет). */
async function microtasks(times = 6): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

interface Harness {
  telemetry: CallTelemetry;
  timers: ReturnType<typeof createFakeTimers>;
  batches: CallTelemetryEvent[][];
  failNext: number;
  calls: number;
}

function createHarness(config: Partial<TelemetryConfig> = {}): Harness {
  const timers = createFakeTimers();
  const harness: Harness = {
    telemetry: null as unknown as CallTelemetry,
    timers,
    batches: [],
    failNext: 0,
    calls: 0,
  };
  let eventCounter = 0;
  harness.telemetry = new CallTelemetry(
    {
      callId: 'call-1',
      sessionId: 'session-1',
      peerRole: 'caller',
      selectedCandidatePairId: null,
      statsIntervalMs: null,
      browserName: 'Chrome',
      browserVersion: '140.0.0.0',
      clientPlatform: 'Win32',
      networkType: '4g',
    },
    async (events) => {
      harness.calls += 1;
      if (harness.failNext > 0) {
        harness.failNext -= 1;
        throw new Error('network is down');
      }
      harness.batches.push(events);
    },
    { ...BASE_CONFIG, ...config },
    {
      timers,
      now: () => 1_700_000_000_000,
      createId: () => {
        eventCounter += 1;
        return `evt-${eventCounter}`;
      },
    },
  );
  return harness;
}

test('событие содержит schemaVersion/eventId/браузер/сеть и details как объект', () => {
  const harness = createHarness();
  harness.telemetry.event('session_started', {
    details: { kind: 'audio', polite: false },
    restartAttempt: 2,
    restartAttemptId: 'attempt-x',
    selectedCandidatePairId: 'pair-1',
    statsIntervalMs: 500,
  });

  const [queued] = (harness.telemetry as unknown as { queue: Array<{ event: CallTelemetryEvent }> }).queue;
  assert.ok(queued);
  const event = queued.event;
  assert.equal(event.schemaVersion, TELEMETRY_SCHEMA_VERSION);
  assert.equal(event.eventId, 'evt-1');
  assert.equal(event.event, 'session_started');
  assert.equal(event.timestamp, new Date(1_700_000_000_000).toISOString());
  assert.equal(event.callId, 'call-1');
  assert.equal(event.sessionId, 'session-1');
  assert.equal(event.peerRole, 'caller');
  assert.equal(event.restartAttempt, 2);
  assert.equal(event.restartAttemptId, 'attempt-x');
  assert.equal(event.browserName, 'Chrome');
  assert.equal(event.browserVersion, '140.0.0.0');
  assert.equal(event.clientPlatform, 'Win32');
  assert.equal(event.networkType, '4g');
  assert.equal(event.selectedCandidatePairId, 'pair-1');
  assert.equal(event.statsIntervalMs, 500);
  assert.equal(typeof event.details, 'object');
  assert.equal(Array.isArray(event.details), false);
  assert.deepEqual(event.details, { kind: 'audio', polite: false });
});

test('details-строка не попадает в событие: поле всегда JSON-объект', () => {
  const harness = createHarness();
  harness.telemetry.event('call_started', {
    // Клиент не должен так делать, но контракт защищён на уровне типа события.
    details: '{"kind":"audio"}' as unknown as Record<string, unknown>,
  });
  const [queued] = (harness.telemetry as unknown as { queue: Array<{ event: CallTelemetryEvent }> }).queue;
  assert.deepEqual(queued?.event.details, {});
});

test('batch отправляется при достижении batchSize и не блокирует вызывающий код', async () => {
  const harness = createHarness({ batchSize: 2 });
  harness.telemetry.start();

  harness.telemetry.event('ice_state_changed', { priority: 'low' });
  assert.equal(harness.calls, 0, 'сеть не вызывается синхронно');

  harness.telemetry.event('ice_restart_attempt');
  harness.timers.advance(0);

  assert.equal(harness.batches.length, 1);
  assert.deepEqual(
    harness.batches[0].map((event) => event.event),
    ['ice_state_changed', 'ice_restart_attempt'],
  );
  assert.equal(harness.telemetry.getQueueSize(), 0);
});

test('retry после сетевой ошибки сохраняет batch и порядок', async () => {
  const harness = createHarness({ batchSize: 2, retryBaseMs: 100 });
  harness.telemetry.start();
  harness.failNext = 1;

  harness.telemetry.event('call_ended');
  harness.telemetry.event('session_ended');
  harness.timers.advance(0);
  await microtasks();
  assert.equal(harness.calls, 1);
  assert.equal(harness.batches.length, 0);
  assert.equal(harness.telemetry.getQueueSize(), 2, 'batch вернулся в очередь');

  harness.timers.advance(100);
  await microtasks();
  assert.equal(harness.calls, 2);
  assert.deepEqual(
    harness.batches[0].map((event) => event.event),
    ['call_ended', 'session_ended'],
  );
  assert.equal(harness.telemetry.getDroppedCount(), 0);
});

test('batch отбрасывается после исчерпания retry, счётчик dropped растёт', async () => {
  const harness = createHarness({ batchSize: 1, maxRetries: 1, retryBaseMs: 100 });
  harness.telemetry.start();
  harness.failNext = 10;

  harness.telemetry.event('stats_sample', { priority: 'low' });
  harness.timers.advance(0); // первая попытка
  await microtasks();
  assert.equal(harness.telemetry.getQueueSize(), 1);

  harness.timers.advance(100); // retry → снова ошибка → batch отброшен
  await microtasks();
  assert.equal(harness.telemetry.getDroppedCount(), 1);
  assert.equal(harness.telemetry.getQueueSize(), 0);
});

test('backpressure: при переполнении буфера первыми уходят низкоприоритетные', () => {
  const harness = createHarness({ batchSize: 100, maxBuffer: 3 });
  harness.telemetry.start();

  harness.telemetry.event('stats_sample', { priority: 'low' });
  harness.telemetry.event('candidate_ignored', { priority: 'low' });
  harness.telemetry.event('call_ended');
  // Буфер полон (3), следующее low-событие вытесняет более старое low.
  harness.telemetry.event('stats_sample', { priority: 'low' });

  assert.equal(harness.telemetry.getQueueSize(), 3);
  assert.equal(harness.telemetry.getDroppedCount(), 1);

  const queue = (harness.telemetry as unknown as {
    queue: Array<{ event: CallTelemetryEvent; priority: string }>;
  }).queue;
  assert.ok(
    queue.some((item) => item.event.event === 'call_ended'),
    'критическое событие не выброшено',
  );
  assert.equal(queue.filter((item) => item.event.event === 'stats_sample').length, 1);
});

test('close(): прекращает приём, отправляет остаток и больше не принимает события', async () => {
  const harness = createHarness({ batchSize: 10 });
  harness.telemetry.start();

  harness.telemetry.event('call_ended', { details: { reason: 'hangup' } });
  await harness.telemetry.close();
  assert.equal(harness.batches.length, 1);
  assert.equal(harness.batches[0][0].event, 'call_ended');

  harness.telemetry.event('session_ended');
  harness.timers.advance(60_000);
  assert.equal(harness.batches.length, 1, 'после close события игнорируются');
  assert.equal(harness.telemetry.getQueueSize(), 0);
});

test('beginSession переключает sessionId в пределах одного логического звонка', () => {
  const harness = createHarness();
  harness.telemetry.event('session_ended');
  harness.telemetry.beginSession('session-2');
  harness.telemetry.event('session_started');

  const queue = (harness.telemetry as unknown as { queue: Array<{ event: CallTelemetryEvent }> }).queue;
  assert.equal(queue[0]?.event.sessionId, 'session-1');
  assert.equal(queue[1]?.event.sessionId, 'session-2');
  assert.equal(queue[0]?.event.callId, queue[1]?.event.callId, 'callId общий для звонка');
});

test('детектор браузера и networkType не выдумывают значения', () => {
  const chrome = detectBrowserContext(
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36',
    'Win32',
    { effectiveType: '3g' },
  );
  assert.equal(chrome.browserName, 'Chrome');
  assert.equal(chrome.browserVersion, '140.0.0.0');
  assert.equal(chrome.clientPlatform, 'Win32');
  assert.equal(chrome.networkType, '3g');

  const unknown = detectBrowserContext('curl/8.0', null, null);
  assert.equal(unknown.browserName, null);
  assert.equal(unknown.networkType, null);

  const safari = detectBrowserContext(
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 Version/17.5 Safari/605.1.15',
    'MacIntel',
    null,
  );
  assert.equal(safari.browserName, 'Safari');
  assert.equal(safari.browserVersion, '17.5');
});

test('createCallTelemetry заполняет контекст звонка и браузера по умолчанию', () => {
  const config: TelemetryConfig = { ...BASE_CONFIG };
  const telemetry = createCallTelemetry({
    callId: 'call-42',
    sessionId: 'sess-42',
    peerRole: 'callee',
    transport: async () => undefined,
    config,
  });
  const context = telemetry.getContext();
  assert.equal(context.callId, 'call-42');
  assert.equal(context.sessionId, 'sess-42');
  assert.equal(context.peerRole, 'callee');
  assert.equal(context.selectedCandidatePairId, null);
  assert.equal(context.statsIntervalMs, null);
  // В Node нет Network Information API — поле остаётся null, а не выдуманным.
  assert.equal(context.networkType, null);
});
