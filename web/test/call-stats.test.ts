/**
 * Разбор RTCStatsReport: выбранная candidate pair, delta между snapshot,
 * сброс delta при смене SSRC/stats object, отсутствующие поля разных браузеров.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { StatsAggregator, emptyStatsSummary, isDegraded } from '../src/lib/call-stats.ts';
import type { StatsRecordLike } from '../src/lib/call-stats.ts';
import { fakeReport } from './harness.ts';

function healthyReport(overrides: {
  pairId?: string;
  pairRttSeconds?: number;
  inbound?: StatsRecordLike;
  outbound?: StatsRecordLike;
  transport?: StatsRecordLike;
  pair?: StatsRecordLike;
} = {}): StatsRecordLike[] {
  const pairId = overrides.pairId ?? 'pair-1';
  return [
    overrides.transport ?? { id: 'transport-1', type: 'transport', selectedCandidatePairId: pairId },
    overrides.pair ?? {
      id: pairId,
      type: 'candidate-pair',
      state: 'succeeded',
      nominated: true,
      currentRoundTripTime: overrides.pairRttSeconds ?? 0.1,
      availableOutgoingBitrate: 64_000,
      localCandidateId: 'local-1',
      remoteCandidateId: 'remote-1',
    },
    { id: 'local-1', type: 'local-candidate', candidateType: 'host' },
    { id: 'remote-1', type: 'remote-candidate', candidateType: 'srflx' },
    overrides.inbound ?? {
      id: 'in-1',
      type: 'inbound-rtp',
      kind: 'audio',
      ssrc: '111',
      packetsLost: 2,
      packetsReceived: 200,
      bytesReceived: 1_000,
      audioLevel: 0.3,
      jitter: 0.02,
    },
    overrides.outbound ?? {
      id: 'out-1',
      type: 'outbound-rtp',
      kind: 'audio',
      ssrc: '222',
      bytesSent: 900,
    },
  ];
}

test('выбранная candidate pair и первый snapshot: cumulative есть, delta ещё нет', () => {
  const aggregator = new StatsAggregator();
  const summary = aggregator.sample(fakeReport(healthyReport()), 2_000, 1_000);

  assert.equal(summary.candidatePairId, 'pair-1');
  assert.equal(summary.localCandidateType, 'host');
  assert.equal(summary.remoteCandidateType, 'srflx');
  assert.equal(summary.rttMs, 100);
  assert.equal(summary.availableOutgoingBitrateBps, 64_000);
  assert.equal(summary.packetsLost, 2);
  assert.equal(summary.packetsReceived, 200);
  assert.equal(summary.audioInBytes, 1_000);
  assert.equal(summary.audioOutBytes, 900);
  assert.equal(summary.audioInLevel, 0.3);
  assert.equal(summary.jitterMs, 20);
  assert.equal(summary.statsIntervalMs, 2_000);
  assert.equal(summary.sampledAt, 1_000);

  assert.equal(summary.deltaReset, true, 'первый snapshot — baseline, delta не считается');
  assert.equal(summary.intervalMs, 0);
  assert.equal(summary.bitrateInBps, null);
  assert.equal(summary.bitrateOutBps, null);
  assert.equal(summary.intervalLossPercent, null);
  assert.equal(summary.packetsLostDelta, 0);
});

test('bitrate и packet loss считаются по delta между snapshot', () => {
  const aggregator = new StatsAggregator();
  aggregator.sample(fakeReport(healthyReport()), 2_000, 1_000);
  const summary = aggregator.sample(
    fakeReport(
      healthyReport({
        inbound: {
          id: 'in-1',
          type: 'inbound-rtp',
          kind: 'audio',
          ssrc: '111',
          packetsLost: 7,
          packetsReceived: 300,
          bytesReceived: 5_000,
          audioLevel: 0.4,
          jitter: 0.03,
        },
        outbound: { id: 'out-1', type: 'outbound-rtp', kind: 'audio', ssrc: '222', bytesSent: 2_900 },
      }),
    ),
    2_000,
    2_000,
  );

  assert.equal(summary.deltaReset, false);
  assert.equal(summary.intervalMs, 1_000);
  assert.equal(summary.packetsLostDelta, 5);
  assert.equal(summary.packetsReceivedDelta, 100);
  assert.equal(summary.intervalLossPercent, (5 / 105) * 100);
  assert.equal(summary.bitrateInBps, 32_000);
  assert.equal(summary.bitrateOutBps, 16_000);
  assert.equal(summary.audioInBytesDelta, 4_000);
  assert.equal(summary.audioOutBytesDelta, 2_000);
  // cumulative-значения не подменяются интервальными.
  assert.equal(summary.packetsLost, 7);
  assert.equal(summary.lossPercent, (7 / 307) * 100);
});

test('смена SSRC сбрасывает delta, не смешивая счётчики разных потоков', () => {
  const aggregator = new StatsAggregator();
  aggregator.sample(fakeReport(healthyReport()), 2_000, 1_000);
  const summary = aggregator.sample(
    fakeReport(
      healthyReport({
        inbound: {
          id: 'in-1',
          type: 'inbound-rtp',
          kind: 'audio',
          ssrc: '999',
          packetsLost: 1,
          packetsReceived: 10,
          bytesReceived: 500,
        },
        outbound: { id: 'out-1', type: 'outbound-rtp', kind: 'audio', ssrc: '222', bytesSent: 900 },
      }),
    ),
    2_000,
    2_000,
  );

  assert.equal(summary.deltaReset, true);
  assert.equal(summary.inboundSsrc, '999');
  assert.equal(summary.bitrateInBps, null);
  assert.equal(summary.intervalLossPercent, null);
  assert.equal(summary.packetsLostDelta, 0);
});

test('смена stats object id (новый inbound-rtp после ICE restart) сбрасывает delta', () => {
  const aggregator = new StatsAggregator();
  aggregator.sample(fakeReport(healthyReport()), 2_000, 1_000);
  const summary = aggregator.sample(
    fakeReport(
      healthyReport({
        inbound: {
          id: 'in-2',
          type: 'inbound-rtp',
          kind: 'audio',
          ssrc: '111',
          packetsLost: 0,
          packetsReceived: 3,
          bytesReceived: 120,
        },
      }),
    ),
    2_000,
    2_000,
  );

  assert.equal(summary.deltaReset, true);
  assert.equal(summary.bitrateInBps, null);
});

test('смена выбранной candidate pair видна по candidatePairId и RTT', () => {
  const aggregator = new StatsAggregator();
  const first = aggregator.sample(fakeReport(healthyReport()), 2_000, 1_000);
  const second = aggregator.sample(
    fakeReport(healthyReport({ pairId: 'pair-2', pairRttSeconds: 0.5 })),
    2_000,
    2_000,
  );

  assert.equal(first.candidatePairId, 'pair-1');
  assert.equal(second.candidatePairId, 'pair-2');
  assert.equal(second.rttMs, 500);
  assert.equal(second.quality, 'poor');
});

test('выбранная пара ищется и без transport.selectedCandidatePairId', () => {
  const aggregator = new StatsAggregator();
  const report = healthyReport({
    transport: { id: 'transport-1', type: 'transport' },
    pair: {
      id: 'pair-7',
      type: 'candidate-pair',
      state: 'succeeded',
      nominated: true,
      currentRoundTripTime: 0.05,
    },
  });

  const summary = aggregator.sample(fakeReport(report), 2_000, 1_000);
  assert.equal(summary.candidatePairId, 'pair-7');
  assert.equal(summary.rttMs, 50);
  assert.equal(summary.localCandidateType, null, 'candidate-записей нет — типы null');
});

test('отсутствующие поля не превращаются в NaN и не ломают сводку', () => {
  const aggregator = new StatsAggregator();
  const report = healthyReport({
    inbound: {
      id: 'in-1',
      type: 'inbound-rtp',
      mediaType: 'audio',
      // packetsLost, packetsReceived, bytesReceived, audioLevel, jitter, ssrc отсутствуют
    },
    outbound: {
      id: 'out-1',
      type: 'outbound-rtp',
      kind: 'audio',
      // bytesSent отсутствует
    },
    pair: {
      id: 'pair-1',
      type: 'candidate-pair',
      state: 'succeeded',
      // currentRoundTripTime и availableOutgoingBitrate отсутствуют
    },
  });
  report.push({
    id: 'remote-in-1',
    type: 'remote-inbound-rtp',
    kind: 'audio',
    roundTripTime: 0.25,
  });

  const summary = aggregator.sample(fakeReport(report), 2_000, 1_000);

  assert.equal(summary.packetsLost, 0);
  assert.equal(summary.packetsReceived, 0);
  assert.equal(summary.audioInBytes, 0);
  assert.equal(summary.audioOutBytes, 0);
  assert.equal(summary.audioInLevel, null);
  assert.equal(summary.jitterMs, null);
  assert.equal(summary.availableOutgoingBitrateBps, null);
  assert.equal(summary.rttMs, 250, 'RTT fallback из remote-inbound-rtp');
  assert.equal(summary.lossPercent, 0);
  assert.ok(Number.isFinite(summary.bitrateInBps ?? 0));
});

test('нет inbound-rtp: baseline сбрасывается, delta не считается', () => {
  const aggregator = new StatsAggregator();
  aggregator.sample(fakeReport(healthyReport()), 2_000, 1_000);
  const summary = aggregator.sample(
    fakeReport([
      { id: 'transport-1', type: 'transport', selectedCandidatePairId: 'pair-1' },
      { id: 'pair-1', type: 'candidate-pair', state: 'succeeded' },
      { id: 'out-1', type: 'outbound-rtp', kind: 'audio', ssrc: '222', bytesSent: 900 },
    ]),
    2_000,
    2_000,
  );

  assert.equal(summary.deltaReset, true);
  assert.equal(summary.packetsReceived, 0);
  assert.equal(summary.bitrateInBps, null);
  assert.equal(summary.intervalLossPercent, null);
});

test('пустой отчёт: unknown-качество и null-поля вместо исключений', () => {
  const aggregator = new StatsAggregator();
  const summary = aggregator.sample(fakeReport([]), 2_000, 1_000);

  assert.equal(summary.quality, 'unknown');
  assert.equal(summary.candidatePairId, null);
  assert.equal(summary.rttMs, null);
  assert.equal(summary.inboundSsrc, null);
  assert.equal(summary.sampledAt, 1_000);
});

test('деградация: poor-качество или потери ≥ 5% поднимают флаг', () => {
  const aggregator = new StatsAggregator();
  aggregator.sample(fakeReport(healthyReport()), 1_000, 1_000);

  const fair = aggregator.sample(fakeReport(healthyReport({ pairRttSeconds: 0.2 })), 1_000, 2_000);
  assert.equal(fair.quality, 'fair');
  assert.equal(isDegraded(fair), false);

  const lossy = aggregator.sample(
    fakeReport(
      healthyReport({
        inbound: {
          id: 'in-1',
          type: 'inbound-rtp',
          kind: 'audio',
          ssrc: '111',
          packetsLost: 20,
          packetsReceived: 300,
          bytesReceived: 5_000,
        },
      }),
    ),
    1_000,
    3_000,
  );
  assert.ok((lossy.intervalLossPercent ?? 0) >= 5);
  assert.equal(isDegraded(lossy), true);

  assert.equal(emptyStatsSummary(2_000, 1).quality, 'unknown');
});
