/**
 * Восстановление ICE: таймер disconnected, повторы с экспоненциальным backoff,
 * исчерпание попыток и отмена при завершении звонка.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { IceRecoveryController, createFakeTimers } from '../src/lib/ice-recovery.ts';
import type {
  IceAttemptFailedInfo,
  IceAttemptInfo,
  IceExhaustedInfo,
  IceRecoveredInfo,
  IceRecoveryPhase,
} from '../src/lib/ice-recovery.ts';

interface Recorder {
  attempts: IceAttemptInfo[];
  failures: IceAttemptFailedInfo[];
  recovered: IceRecoveredInfo[];
  exhausted: IceExhaustedInfo[];
  phases: Array<{ phase: IceRecoveryPhase; attempt: number }>;
  controller: IceRecoveryController;
  timers: ReturnType<typeof createFakeTimers>;
  connected: { value: boolean };
}

function createRecorder(config?: Partial<{
  disconnectedGraceMs: number;
  attemptTimeoutMs: number;
  maxAttempts: number;
  backoffBaseMs: number;
  backoffMaxMs: number;
}>): Recorder {
  const timers = createFakeTimers();
  const recorder: Recorder = {
    attempts: [],
    failures: [],
    recovered: [],
    exhausted: [],
    phases: [],
    connected: { value: false },
    timers,
    controller: null as unknown as IceRecoveryController,
  };
  recorder.controller = new IceRecoveryController(
    {
      disconnectedGraceMs: 1_000,
      attemptTimeoutMs: 1_000,
      maxAttempts: 3,
      backoffBaseMs: 500,
      backoffMaxMs: 2_000,
      ...config,
    },
    {
      onAttempt: (info) => recorder.attempts.push(info),
      onAttemptFailed: (info) => recorder.failures.push(info),
      onRecovered: (info) => recorder.recovered.push(info),
      onExhausted: (info) => recorder.exhausted.push(info),
      onPhaseChange: (phase, detail) => {
        recorder.phases.push({ phase, attempt: detail.attempt });
      },
    },
    {
      timers,
      isConnected: () => recorder.connected.value,
      createId: () => `attempt-${recorder.attempts.length + 1}`,
    },
  );
  return recorder;
}

test('временный disconnected: таймер отменяется восстановлением, restart не нужен', () => {
  const rec = createRecorder();

  rec.controller.handleConnectionState('disconnected');
  assert.equal(rec.controller.getPhase(), 'waiting');
  rec.timers.advance(400);

  rec.connected.value = true;
  rec.controller.handleConnectionState('connected');
  rec.timers.advance(10_000);

  assert.equal(rec.controller.getPhase(), 'idle');
  assert.equal(rec.attempts.length, 0, 'restart не запускался');
  assert.equal(rec.recovered.length, 0, 'восстановление без попыток не считается ICE restart');
  assert.equal(rec.exhausted.length, 0);
  assert.equal(rec.timers.pending(), 0, 'все таймеры сняты');
});

test('успешный ICE restart: попытка, восстановление, счётчики сброшены', () => {
  const rec = createRecorder();

  rec.controller.handleConnectionState('disconnected');
  rec.timers.advance(1_000); // grace истёк → попытка 1
  assert.equal(rec.attempts.length, 1);
  assert.equal(rec.attempts[0]?.attempt, 1);
  assert.equal(rec.controller.getPhase(), 'restarting');

  rec.connected.value = true;
  rec.controller.handleConnectionState('connected');
  rec.timers.advance(5_000);

  assert.equal(rec.recovered.length, 1);
  assert.equal(rec.recovered[0]?.attempts, 1);
  assert.equal(rec.recovered[0]?.reason, 'disconnected_timeout');
  assert.equal(rec.controller.getPhase(), 'idle');
  assert.equal(rec.controller.getAttempt(), 0);
  assert.equal(rec.exhausted.length, 0);
  assert.equal(rec.timers.pending(), 0);
});

test('failed и исчерпание retry: backoff, willRetry, окончательная причина', () => {
  const rec = createRecorder({ maxAttempts: 2, backoffBaseMs: 500, backoffMaxMs: 2_000 });

  rec.controller.requestRestart('connection_failed');
  assert.equal(rec.attempts.length, 1);
  assert.equal(rec.timers.now(), 1_000_000);

  rec.timers.advance(1_000); // таймаут попытки 1 → backoff 500
  assert.deepEqual(
    rec.failures.map((item) => ({ attempt: item.attempt, error: item.error, willRetry: item.willRetry })),
    [{ attempt: 1, error: 'restart_timeout', willRetry: true }],
  );
  assert.equal(rec.controller.getPhase(), 'backoff');

  rec.timers.advance(500); // старт попытки 2
  assert.equal(rec.attempts.length, 2);

  rec.timers.advance(1_000); // таймаут попытки 2 → исчерпание
  assert.deepEqual(
    rec.failures.map((item) => ({ attempt: item.attempt, willRetry: item.willRetry })),
    [
      { attempt: 1, willRetry: true },
      { attempt: 2, willRetry: false },
    ],
  );
  assert.equal(rec.exhausted.length, 1);
  assert.equal(rec.exhausted[0]?.attempts, 2);
  assert.equal(rec.exhausted[0]?.reason, 'connection_failed');
  assert.equal(rec.exhausted[0]?.lastError, 'restart_timeout');
  assert.equal(rec.controller.getPhase(), 'exhausted');
  assert.equal(rec.timers.pending(), 0);
  assert.equal(rec.recovered.length, 0);
});

test('экспоненциальный backoff настраивается и упирается в потолок', () => {
  const rec = createRecorder({ maxAttempts: 4, backoffBaseMs: 100, backoffMaxMs: 300 });
  const startedAt: number[] = [];

  rec.controller.requestRestart('connection_failed');
  startedAt.push(rec.timers.now());
  // Каждый таймаут запускает следующий backoff: 100, 200, 300 (потолок).
  for (let i = 0; i < 3; i += 1) {
    rec.timers.advance(1_000); // таймаут текущей попытки
    const delay = Math.min(300, 100 * 2 ** i);
    rec.timers.advance(delay);
    startedAt.push(rec.timers.now());
  }

  const gaps = startedAt.slice(1).map((at, index) => at - (startedAt[index] ?? at));
  assert.deepEqual(gaps, [1_000 + 100, 1_000 + 200, 1_000 + 300]);
  assert.equal(rec.attempts.length, 4);
});

test('завершение звонка во время restart: отмена таймеров и никаких callbacks', () => {
  const rec = createRecorder({ maxAttempts: 5 });

  rec.controller.handleConnectionState('disconnected');
  rec.timers.advance(1_000); // попытка 1 в полёте
  assert.equal(rec.controller.getPhase(), 'restarting');

  // Ровно то, что делает CallSession.hangup(): сначала cancel recovery.
  rec.controller.cancel('hangup');
  assert.equal(rec.controller.getPhase(), 'closed');
  assert.equal(rec.timers.pending(), 0, 'attempt/backoff/grace таймеры сняты');

  const attemptsBefore = rec.attempts.length;
  const failuresBefore = rec.failures.length;
  rec.timers.advance(120_000);

  assert.equal(rec.attempts.length, attemptsBefore, 'новых попыток нет');
  assert.equal(rec.failures.length, failuresBefore, 'неудач после закрытия нет');
  assert.equal(rec.exhausted.length, 0);
  assert.equal(rec.recovered.length, 0);
});

test('cancel во время backoff не запускает следующую попытку', () => {
  const rec = createRecorder({ maxAttempts: 5, backoffBaseMs: 1_000 });
  rec.controller.requestRestart('connection_failed');
  rec.timers.advance(1_000); // таймаут → backoff
  assert.equal(rec.controller.getPhase(), 'backoff');

  rec.controller.cancel('hangup');
  rec.timers.advance(60_000);

  assert.equal(rec.attempts.length, 1);
  assert.equal(rec.timers.pending(), 0);
});

test('restartIce выбросил исключение: попытка падает сразу, без ожидания таймаута', () => {
  const rec = createRecorder({ maxAttempts: 2 });

  rec.controller.requestRestart('connection_failed');
  rec.controller.attemptFailed('restartIce is not allowed');

  assert.equal(rec.failures.length, 1);
  assert.equal(rec.failures[0]?.error, 'restartIce is not allowed');
  assert.equal(rec.failures[0]?.willRetry, true);
  assert.equal(rec.controller.getPhase(), 'backoff');
  assert.equal(rec.timers.pending(), 1);
});

test('maxAttempts=0: ICE restart выключен, причина понятна', () => {
  const rec = createRecorder({ maxAttempts: 0 });

  rec.controller.handleConnectionState('failed');

  assert.equal(rec.attempts.length, 0);
  assert.equal(rec.exhausted.length, 1);
  assert.equal(rec.exhausted[0]?.reason, 'ice_restart_disabled');
  assert.equal(rec.exhausted[0]?.attempts, 0);
  assert.equal(rec.controller.getPhase(), 'exhausted');
});

test('ручной requestRestart начинает новый инцидент и не дублирует активную попытку', () => {
  const rec = createRecorder({ maxAttempts: 3 });

  rec.connected.value = true;
  rec.controller.requestRestart('ws_resync');
  assert.equal(rec.attempts.length, 1);
  assert.equal(rec.attempts[0]?.reason, 'ws_resync');

  // Пока попытка в полёте, повторный ручной restart игнорируется (нет параллелизма).
  rec.controller.requestRestart('ws_resync');
  assert.equal(rec.attempts.length, 1);

  rec.timers.advance(1_000); // соединение всё ещё connected → попытка успешна
  assert.equal(rec.recovered.length, 1);
  assert.equal(rec.recovered[0]?.attempts, 1);

  // Следующий инцидент начинается с попытки 1, а не с 2.
  rec.controller.requestRestart('ws_resync');
  assert.equal(rec.attempts.length, 2);
  assert.equal(rec.attempts[1]?.attempt, 1);
});
