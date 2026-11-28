/**
 * Разбор RTCStatsReport и расчёт интервальных метрик.
 *
 * Правила, которые здесь соблюдаются сознательно:
 *  - используются только выбранная candidate pair и «свои» audio-записи;
 *  - cumulative-счётчики и значения за интервал не смешиваются: наружу
 *    отдаются оба набора полей (`packetsLost` и `packetsLostDelta` и т.п.);
 *  - delta сбрасывается при смене stats object id или SSRC (после ICE restart
 *    браузер создаёт новые inbound/outbound-rtp записи);
 *  - отсутствующие поля (разные браузеры) не превращаются в NaN/0-деление;
 *  - модуль чистый: не трогает DOM, таймеры и сеть — его легко тестировать.
 */

export type CallQuality = 'good' | 'fair' | 'poor' | 'unknown';

export interface StatsRecordLike {
  readonly id?: string;
  readonly type?: string;
  readonly [key: string]: unknown;
}

export interface StatsReportLike {
  forEach(callback: (record: StatsRecordLike) => void): void;
}

export interface CallStatsSummary {
  /* ---- cumulative (с начала сессии) ---- */
  /** RTT по выбранной паре, мс. */
  rttMs: number | null;
  /** Суммарно потеряно пакетов с начала сессии. */
  packetsLost: number;
  /** Суммарно получено пакетов с начала сессии. */
  packetsReceived: number;
  /** Потери за всю сессию, %. */
  lossPercent: number;
  /** Всего получено байт аудио. */
  audioInBytes: number;
  /** Всего отправлено байт аудио. */
  audioOutBytes: number;
  /** Уровень входящего звука 0..1 (WebRTC audioLevel). */
  audioInLevel: number | null;

  /* ---- за интервал между двумя snapshot ---- */
  /** Потеряно пакетов за интервал. */
  packetsLostDelta: number;
  /** Получено пакетов за интервал. */
  packetsReceivedDelta: number;
  /** Потери за интервал, % (null — не было пакетов). */
  intervalLossPercent: number | null;
  /** Входящий аудиобитрейт, бит/с. */
  bitrateInBps: number | null;
  /** Исходящий аудиобитрейт, бит/с. */
  bitrateOutBps: number | null;
  /** Байт аудио за интервал. */
  audioInBytesDelta: number;
  audioOutBytesDelta: number;
  /** Джиттер, мс. */
  jitterMs: number | null;
  /** Длительность интервала, мс. */
  intervalMs: number;
  /** true — предыдущий snapshot не сопоставим (сменился SSRC/stats object). */
  deltaReset: boolean;

  /* ---- контекст ---- */
  quality: CallQuality;
  /** id выбранной candidate-pair (не содержит IP-адресов). */
  candidatePairId: string | null;
  localCandidateType: string | null;
  remoteCandidateType: string | null;
  /** Оценка доступной исходящей полосы, бит/с. */
  availableOutgoingBitrateBps: number | null;
  /** SSRC выбранных audio-потоков (для диагностики смены потока). */
  inboundSsrc: string | null;
  outboundSsrc: string | null;
  /** Частота опроса, с которой получен snapshot. */
  statsIntervalMs: number;
  /** Момент snapshot (ms epoch). */
  sampledAt: number;
}

interface PrimaryInbound {
  id: string;
  ssrc: string | null;
  packetsLost: number;
  packetsReceived: number;
  bytesReceived: number;
  audioLevel: number | null;
  jitterMs: number | null;
}

interface PrimaryOutbound {
  id: string;
  ssrc: string | null;
  bytesSent: number;
}

interface PrimaryCandidatePair {
  id: string;
  rttMs: number | null;
  availableOutgoingBitrateBps: number | null;
  localCandidateId: string | null;
  remoteCandidateId: string | null;
}

interface PreviousCounters {
  inboundId: string;
  inboundSsrc: string | null;
  inboundBytes: number;
  outboundId: string | null;
  outboundSsrc: string | null;
  outboundBytes: number;
  packetsLost: number;
  packetsReceived: number;
  at: number;
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function mediaKind(record: StatsRecordLike): string | null {
  return asString(record.kind) ?? asString(record.mediaType);
}

/** seconds → ms; undefined/NaN → null. */
function secondsToMs(value: unknown): number | null {
  const seconds = asNumber(value);
  return seconds === null ? null : Math.max(0, Math.round(seconds * 1000));
}

function readRecords(report: StatsReportLike): Map<string, StatsRecordLike> {
  const records = new Map<string, StatsRecordLike>();
  report.forEach((record) => {
    if (!record) return;
    const id = asString(record.id) ?? `${record.type ?? 'unknown'}-${records.size}`;
    records.set(id, record);
  });
  return records;
}

function typeOf(record: StatsRecordLike): string {
  return asString(record.type) ?? '';
}

function isCandidatePair(record: StatsRecordLike): boolean {
  return typeOf(record) === 'candidate-pair';
}

/**
 * Выбранная пара: transport.selectedCandidatePairId, иначе nominated+succeeded,
 * иначе любая succeeded-пара, по которой уже ходят байты. Некоторые браузеры
 * не выставляют nominated, поэтому жёсткое условие по нему не используется.
 */
function selectCandidatePair(records: Map<string, StatsRecordLike>): PrimaryCandidatePair | null {
  let chosen: StatsRecordLike | null = null;

  for (const record of records.values()) {
    if (typeOf(record) !== 'transport') continue;
    const selectedId = asString(record.selectedCandidatePairId);
    if (selectedId) {
      const pair = records.get(selectedId);
      if (pair && isCandidatePair(pair)) {
        chosen = pair;
        break;
      }
    }
  }

  if (!chosen) {
    for (const record of records.values()) {
      if (!isCandidatePair(record)) continue;
      if (record.nominated === true && record.state === 'succeeded') {
        chosen = record;
        break;
      }
    }
  }

  if (!chosen) {
    for (const record of records.values()) {
      if (!isCandidatePair(record)) continue;
      if (record.state === 'succeeded') {
        chosen = record;
        break;
      }
    }
  }

  if (!chosen) return null;
  return {
    id: asString(chosen.id) ?? 'candidate-pair',
    rttMs: secondsToMs(chosen.currentRoundTripTime),
    availableOutgoingBitrateBps: asNumber(chosen.availableOutgoingBitrate),
    localCandidateId: asString(chosen.localCandidateId),
    remoteCandidateId: asString(chosen.remoteCandidateId),
  };
}

function selectInboundAudio(records: Map<string, StatsRecordLike>): PrimaryInbound | null {
  let best: PrimaryInbound | null = null;
  for (const record of records.values()) {
    if (typeOf(record) !== 'inbound-rtp' || mediaKind(record) !== 'audio') continue;
    const candidate: PrimaryInbound = {
      id: asString(record.id) ?? 'inbound-rtp-audio',
      ssrc: asString(record.ssrc),
      packetsLost: Math.max(0, asNumber(record.packetsLost) ?? 0),
      packetsReceived: Math.max(0, asNumber(record.packetsReceived) ?? 0),
      bytesReceived: Math.max(0, asNumber(record.bytesReceived) ?? 0),
      audioLevel: asNumber(record.audioLevel),
      jitterMs: secondsToMs(record.jitter),
    };
    // Оставляем запись с наибольшим числом принятых пакетов: остальные
    // (например, «замороженные» после renegotiation) только портят сумму.
    if (!best || candidate.packetsReceived >= best.packetsReceived) best = candidate;
  }
  return best;
}

function selectOutboundAudio(records: Map<string, StatsRecordLike>): PrimaryOutbound | null {
  let best: PrimaryOutbound | null = null;
  for (const record of records.values()) {
    if (typeOf(record) !== 'outbound-rtp' || mediaKind(record) !== 'audio') continue;
    const candidate: PrimaryOutbound = {
      id: asString(record.id) ?? 'outbound-rtp-audio',
      ssrc: asString(record.ssrc),
      bytesSent: Math.max(0, asNumber(record.bytesSent) ?? 0),
    };
    if (!best || candidate.bytesSent >= best.bytesSent) best = candidate;
  }
  return best;
}

function fallbackRemoteRttMs(records: Map<string, StatsRecordLike>): number | null {
  for (const record of records.values()) {
    if (typeOf(record) !== 'remote-inbound-rtp' || mediaKind(record) !== 'audio') continue;
    const rtt = secondsToMs(record.roundTripTime);
    if (rtt !== null) return rtt;
  }
  return null;
}

function candidateType(records: Map<string, StatsRecordLike>, id: string | null): string | null {
  if (!id) return null;
  const record = records.get(id);
  if (!record) return null;
  return asString(record.candidateType);
}

function computeQuality(
  rttMs: number | null,
  intervalLossPercent: number | null,
  hasTraffic: boolean,
): CallQuality {
  if (rttMs === null && !hasTraffic && intervalLossPercent === null) return 'unknown';
  const rtt = rttMs ?? 0;
  const loss = intervalLossPercent ?? 0;
  if (rtt < 150 && loss < 2) return 'good';
  if (rtt < 400 && loss < 5) return 'fair';
  return 'poor';
}

/** true — качество ухудшилось: чаще опрашиваем getStats и шлём телеметрию. */
export function isDegraded(summary: CallStatsSummary): boolean {
  if (summary.quality === 'poor') return true;
  if (summary.quality === 'unknown') return false;
  if (summary.rttMs !== null && summary.rttMs >= 400) return true;
  if (summary.intervalLossPercent !== null && summary.intervalLossPercent >= 5) return true;
  return false;
}

/**
 * Хранит предыдущий snapshot и считает delta. Один экземпляр на CallSession;
 * `reset()` вызывается при завершении звонка.
 */
export class StatsAggregator {
  private previous: PreviousCounters | null = null;

  reset(): void {
    this.previous = null;
  }

  sample(report: StatsReportLike, statsIntervalMs: number, now: number = Date.now()): CallStatsSummary {
    const records = readRecords(report);
    const pair = selectCandidatePair(records);
    const inbound = selectInboundAudio(records);
    const outbound = selectOutboundAudio(records);

    const rttMs = pair?.rttMs ?? fallbackRemoteRttMs(records);
    const localType = candidateType(records, pair?.localCandidateId ?? null);
    const remoteType = candidateType(records, pair?.remoteCandidateId ?? null);

    const cumulativeLost = inbound?.packetsLost ?? 0;
    const cumulativeReceived = inbound?.packetsReceived ?? 0;
    const cumulativeTotal = cumulativeLost + cumulativeReceived;
    const lossPercent = cumulativeTotal > 0 ? (cumulativeLost / cumulativeTotal) * 100 : 0;

    let packetsLostDelta = 0;
    let packetsReceivedDelta = 0;
    let audioInBytesDelta = 0;
    let audioOutBytesDelta = 0;
    let intervalMs = 0;
    let deltaReset = false;
    let bitrateInBps: number | null = null;
    let bitrateOutBps: number | null = null;

    const previous = this.previous;
    if (!inbound) {
      // Нет inbound-rtp: соединение ещё не приняло ни одного пакета или
      // браузер не отдаёт запись. Delta не считаем, baseline сбрасываем.
      this.previous = null;
      deltaReset = previous !== null;
    } else {
      const inboundComparable =
        previous !== null &&
        previous.inboundId === inbound.id &&
        previous.inboundSsrc === inbound.ssrc;
      const outboundComparable =
        previous !== null &&
        outbound !== null &&
        previous.outboundId === outbound.id &&
        previous.outboundSsrc === outbound.ssrc;
      const elapsed = previous === null ? 0 : now - previous.at;
      deltaReset = !inboundComparable || !outboundComparable || elapsed <= 0;

      if (!deltaReset && previous !== null && outbound) {
        intervalMs = elapsed;
        packetsLostDelta = Math.max(0, inbound.packetsLost - previous.packetsLost);
        packetsReceivedDelta = Math.max(0, inbound.packetsReceived - previous.packetsReceived);
        audioInBytesDelta = Math.max(0, inbound.bytesReceived - previous.inboundBytes);
        audioOutBytesDelta = Math.max(0, outbound.bytesSent - previous.outboundBytes);
        bitrateInBps = Math.round((audioInBytesDelta * 8 * 1000) / elapsed);
        bitrateOutBps = Math.round((audioOutBytesDelta * 8 * 1000) / elapsed);
      } else if (previous !== null && !outbound) {
        // Входящий поток ещё есть, исходящий временно отсутствует: потери
        // считать можно, битрейт — нет.
        intervalMs = Math.max(0, elapsed);
        packetsLostDelta = Math.max(0, inbound.packetsLost - previous.packetsLost);
        packetsReceivedDelta = Math.max(0, inbound.packetsReceived - previous.packetsReceived);
        audioInBytesDelta = Math.max(0, inbound.bytesReceived - previous.inboundBytes);
        bitrateInBps = intervalMs > 0
          ? Math.round((audioInBytesDelta * 8 * 1000) / intervalMs)
          : null;
      }

      this.previous = {
        inboundId: inbound.id,
        inboundSsrc: inbound.ssrc,
        inboundBytes: inbound.bytesReceived,
        outboundId: outbound?.id ?? null,
        outboundSsrc: outbound?.ssrc ?? null,
        outboundBytes: outbound?.bytesSent ?? 0,
        packetsLost: inbound.packetsLost,
        packetsReceived: inbound.packetsReceived,
        at: now,
      };
    }

    const intervalTotal = packetsLostDelta + packetsReceivedDelta;
    const intervalLossPercent =
      !deltaReset && intervalTotal > 0 ? (packetsLostDelta / intervalTotal) * 100 : null;

    return {
      rttMs,
      packetsLost: cumulativeLost,
      packetsReceived: cumulativeReceived,
      lossPercent,
      audioInBytes: inbound?.bytesReceived ?? 0,
      audioOutBytes: outbound?.bytesSent ?? 0,
      audioInLevel: inbound?.audioLevel ?? null,

      packetsLostDelta,
      packetsReceivedDelta,
      intervalLossPercent,
      bitrateInBps,
      bitrateOutBps,
      audioInBytesDelta,
      audioOutBytesDelta,
      jitterMs: inbound?.jitterMs ?? null,
      intervalMs,
      deltaReset,

      quality: computeQuality(rttMs, intervalLossPercent, cumulativeTotal > 0),
      candidatePairId: pair?.id ?? null,
      localCandidateType: localType,
      remoteCandidateType: remoteType,
      availableOutgoingBitrateBps: pair?.availableOutgoingBitrateBps ?? null,
      inboundSsrc: inbound?.ssrc ?? null,
      outboundSsrc: outbound?.ssrc ?? null,
      statsIntervalMs,
      sampledAt: now,
    };
  }
}

/** Пустая сводка: используется до первого getStats и при закрытом соединении. */
export function emptyStatsSummary(statsIntervalMs: number, now: number = Date.now()): CallStatsSummary {
  return {
    rttMs: null,
    packetsLost: 0,
    packetsReceived: 0,
    lossPercent: 0,
    audioInBytes: 0,
    audioOutBytes: 0,
    audioInLevel: null,
    packetsLostDelta: 0,
    packetsReceivedDelta: 0,
    intervalLossPercent: null,
    bitrateInBps: null,
    bitrateOutBps: null,
    audioInBytesDelta: 0,
    audioOutBytesDelta: 0,
    jitterMs: null,
    intervalMs: 0,
    deltaReset: false,
    quality: 'unknown',
    candidatePairId: null,
    localCandidateType: null,
    remoteCandidateType: null,
    availableOutgoingBitrateBps: null,
    inboundSsrc: null,
    outboundSsrc: null,
    statsIntervalMs,
    sampledAt: now,
  };
}
