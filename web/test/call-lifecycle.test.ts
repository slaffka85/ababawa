/**
 * Завершение звонка во время ICE restart + конфигурируемость таймаутов.
 *
 * CallSession.hangup() сделать юнит-тестом нельзя без настоящего
 * RTCPeerConnection, поэтому здесь проверяется ровно та композиция, которую он
 * выполняет и от которой зависят наблюдаемые гарантии:
 *   recovery.cancel() → negotiator.close() → телеметрия session_ended → close().
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Switchboard } from './harness.ts';
import { IceRecoveryController, createFakeTimers } from '../src/lib/ice-recovery.ts';
import { CallTelemetry } from '../src/lib/telemetry.ts';
import type { CallTelemetryEvent } from '../src/lib/telemetry.ts';
import { DEFAULT_CALL_CONFIG, loadCallConfig, normalizeCallConfig } from '../src/lib/call-config.ts';

test('завершение звонка во время ICE restart: таймеры сняты, сигналы не уходят, телеметрия закрыта', async () => {
  const timers = createFakeTimers();
  const board = new Switchboard();
  const impolite = board.add('impolite', false);
  board.add('polite', true);

  await impolite.negotiator.negotiationNeeded();
  await board.settle();
  const signalsBeforeRestart = impolite.sent.length;

  const batches: CallTelemetryEvent[][] = [];
  let eventCounter = 0;
  const telemetry = new CallTelemetry(
    {
      callId: 'call-1',
      sessionId: 'session-1',
      peerRole: 'caller',
      selectedCandidatePairId: 'pair-1',
      statsIntervalMs: 500,
      browserName: 'Chrome',
      browserVersion: '140.0.0.0',
      clientPlatform: 'Win32',
      networkType: '4g',
    },
    async (events) => {
      batches.push(events);
    },
    {
      batchSize: 20,
      flushIntervalMs: 5_000,
      maxBuffer: 50,
      maxRetries: 2,
      retryBaseMs: 100,
      retryMaxMs: 1_000,
    },
    {
      timers,
      now: () => 1_700_000_000_000,
      createId: () => {
        eventCounter += 1;
        return `evt-${eventCounter}`;
      },
    },
  );
  telemetry.start();

  let connected = false;
  const attempts: number[] = [];
  const exhausted: string[] = [];
  const recovery = new IceRecoveryController(
    {
      disconnectedGraceMs: 1_000,
      attemptTimeoutMs: 2_000,
      maxAttempts: 3,
      backoffBaseMs: 500,
      backoffMaxMs: 2_000,
    },
    {
      onAttempt: (info) => {
        attempts.push(info.attempt);
        telemetry.event('ice_restart_attempt', {
          restartAttempt: info.attempt,
          restartAttemptId: info.attemptId,
          details: { reason: info.reason },
        });
        impolite.negotiator.requestIceRestart(info.reason);
      },
      onAttemptFailed: (info) => {
        telemetry.event('ice_restart_failed', {
          restartAttempt: info.attempt,
          restartAttemptId: info.attemptId,
          details: { error: info.error, will_retry: info.willRetry },
        });
      },
      onRecovered: () => undefined,
      onExhausted: (info) => {
        exhausted.push(info.reason);
        telemetry.event('ice_restart_exhausted', {
          restartAttempt: info.attempts,
          details: { reason: info.reason, last_error: info.lastError },
        });
      },
    },
    { timers, isConnected: () => connected, createId: () => 'attempt-1' },
  );

  recovery.handleConnectionState('disconnected');
  timers.advance(1_000);
  await impolite.negotiator.negotiationNeeded();
  await board.settle();

  assert.deepEqual(attempts, [1], 'restart начался');
  assert.ok(impolite.sent.length > signalsBeforeRestart, 'offer restart отправлен');
  assert.equal(recovery.getPhase(), 'restarting');
  assert.ok(timers.pending() >= 1, 'attempt-таймер взведён');

  // --- CallSession.hangup(): ровно этот порядок ---
  recovery.cancel('hangup');
  impolite.negotiator.close();
  telemetry.event('session_ended', {
    details: { reason: 'hangup' },
    restartAttempt: 1,
    restartAttemptId: 'attempt-1',
  });
  await telemetry.close();

  const signalsAtHangup = impolite.sent.length;
  timers.advance(120_000);
  await board.settle();

  assert.equal(recovery.getPhase(), 'closed');
  assert.equal(attempts.length, 1, 'новых попыток после hangup нет');
  assert.deepEqual(exhausted, [], 'исчерпание не наступает после отмены');
  assert.equal(timers.pending(), 0, 'все таймеры (grace/attempt/backoff/telemetry) сняты');
  assert.equal(impolite.sent.length, signalsAtHangup, 'новые сигналы не отправляются');
  assert.deepEqual(impolite.errors, []);

  const sentEvents = batches.flat().map((event) => event.event);
  assert.ok(sentEvents.includes('ice_restart_attempt'));
  assert.ok(sentEvents.includes('session_ended'), 'session_ended ушёл в финальном batch');
  const sessionEnded = batches.flat().find((event) => event.event === 'session_ended');
  assert.equal(sessionEnded?.restartAttempt, 1);
  assert.equal(sessionEnded?.restartAttemptId, 'attempt-1');
  assert.equal(sessionEnded?.details.reason, 'hangup');

  // После close события игнорируются — «прекращена отправка телеметрии».
  const batchesAfterClose = batches.length;
  telemetry.event('call_ended', { details: { reason: 'hangup' } });
  timers.advance(60_000);
  await Promise.resolve();
  assert.equal(batches.length, batchesAfterClose);
  assert.equal(telemetry.getQueueSize(), 0);

  board.close();
});

test('таймауты, число попыток и backoff конфигурируются с валидными границами', () => {
  const defaults = loadCallConfig();
  assert.equal(defaults.disconnectedGraceMs, DEFAULT_CALL_CONFIG.disconnectedGraceMs);
  assert.equal(defaults.iceRestartAttemptTimeoutMs, DEFAULT_CALL_CONFIG.iceRestartAttemptTimeoutMs);
  assert.equal(defaults.iceRestartMaxAttempts, DEFAULT_CALL_CONFIG.iceRestartMaxAttempts);
  assert.equal(defaults.iceRestartBackoffBaseMs, DEFAULT_CALL_CONFIG.iceRestartBackoffBaseMs);
  assert.equal(defaults.statsIntervalMs, DEFAULT_CALL_CONFIG.statsIntervalMs);
  assert.ok(defaults.statsDegradedIntervalMs < defaults.statsIntervalMs, 'деградация опрашивается чаще');
  assert.ok(defaults.statsRestartIntervalMs < defaults.statsDegradedIntervalMs, 'restart — самый частый');
  assert.ok(defaults.telemetryBatchSize > 0);
  assert.ok(defaults.telemetryMaxBuffer >= defaults.telemetryBatchSize);

  const overridden = loadCallConfig({
    iceRestartMaxAttempts: 7,
    iceRestartAttemptTimeoutMs: 9_000,
    statsIntervalMs: 4_000,
  });
  assert.equal(overridden.iceRestartMaxAttempts, 7);
  assert.equal(overridden.iceRestartAttemptTimeoutMs, 9_000);
  assert.equal(overridden.statsIntervalMs, 4_000);
  // Остальные значения остаются дефолтными.
  assert.equal(overridden.disconnectedGraceMs, DEFAULT_CALL_CONFIG.disconnectedGraceMs);

  const normalized = normalizeCallConfig({
    ...DEFAULT_CALL_CONFIG,
    statsIntervalMs: 10,
    iceRestartAttemptTimeoutMs: 10,
    iceRestartBackoffBaseMs: 5_000,
    iceRestartBackoffMaxMs: 1_000,
    telemetryBatchSize: 0,
  });
  assert.equal(normalized.statsIntervalMs, 250);
  assert.equal(normalized.iceRestartAttemptTimeoutMs, 1_000);
  assert.equal(normalized.telemetryBatchSize, 1);
  assert.ok(
    normalized.iceRestartBackoffMaxMs >= normalized.iceRestartBackoffBaseMs,
    'потолок backoff не ниже базы',
  );
});
