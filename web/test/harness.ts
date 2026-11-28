/**
 * Общие тестовые двойники для модулей звонка.
 *
 * Здесь нет браузера и WebRTC: FakePeer повторяет только тот контракт
 * RTCPeerConnection, который реально использует negotiation-слой
 * (signalingState + setLocal/RemoteDescription + addIceCandidate + restartIce),
 * а Switchboard передаёт сигналы между двумя участниками асинхронно — так же,
 * как это делает backend транзитом через WebSocket.
 *
 * Тесты гоняются встроенным node:test (Node 24, type stripping), поэтому
 * зависимости и сборка не нужны.
 */
import type {
  IceCandidateLike,
  NegotiationSignal,
  OfferCollisionInfo,
  SessionDescriptionLike,
} from '../src/lib/negotiation.ts';
import { SessionNegotiator } from '../src/lib/negotiation.ts';
import type { StatsRecordLike, StatsReportLike } from '../src/lib/call-stats.ts';

/** RTCStatsReport-подобный объект: forEach по массиву записей. */
export function fakeReport(records: StatsRecordLike[]): StatsReportLike {
  return {
    forEach(callback: (record: StatsRecordLike) => void): void {
      for (const record of records) callback(record);
    },
  };
}

/**
 * Минимальный RTCPeerConnection для negotiation-тестов.
 * Каждый локальный offer/answer получает новый ICE ufrag — как при restartIce.
 */
export class FakePeer {
  signalingState = 'stable';
  remoteDescription: SessionDescriptionLike | null = null;
  localDescription: SessionDescriptionLike | null = null;
  readonly candidates: IceCandidateLike[] = [];
  restartIceCalls = 0;
  /** Если задано — restartIce() сразу бросает исключение. */
  failRestartIce: string | null = null;
  setRemoteDescriptionCalls = 0;
  rollbackCalls = 0;

  readonly id: string;

  private ufragCounter = 0;

  constructor(id: string) {
    this.id = id;
  }

  private nextSdp(type: 'offer' | 'answer'): string {
    this.ufragCounter += 1;
    const ufrag = `${this.id}-ufrag-${this.ufragCounter}`;
    return [
      'v=0',
      `o=- ${this.id} 0 IN IP4 0.0.0.0`,
      's=-',
      't=0 0',
      `a=ice-ufrag:${ufrag}`,
      'a=mid:0',
      '',
    ].join('\r\n');
  }

  async setLocalDescription(description?: SessionDescriptionLike): Promise<void> {
    if (description?.type === 'rollback') {
      this.rollbackCalls += 1;
      this.signalingState = 'stable';
      this.localDescription = null;
      return;
    }
    if (!description) {
      // Имплицитный offer: setLocalDescription() без аргументов.
      this.localDescription = { type: 'offer', sdp: this.nextSdp('offer') };
      this.signalingState = 'have-local-offer';
      return;
    }
    this.localDescription = description;
    this.signalingState = description.type === 'answer' ? 'stable' : 'have-local-offer';
  }

  async setRemoteDescription(description: SessionDescriptionLike): Promise<void> {
    this.setRemoteDescriptionCalls += 1;
    this.remoteDescription = description;
    this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable';
  }

  async createAnswer(): Promise<SessionDescriptionLike> {
    return { type: 'answer', sdp: this.nextSdp('answer') };
  }

  async addIceCandidate(candidate: IceCandidateLike): Promise<void> {
    this.candidates.push(candidate);
  }

  restartIce(): void {
    this.restartIceCalls += 1;
    if (this.failRestartIce) throw new Error(this.failRestartIce);
  }
}

export interface Endpoint {
  id: string;
  polite: boolean;
  peer: FakePeer;
  negotiator: SessionNegotiator;
  /** Все исходящие сигналы (offer/answer/candidate). */
  sent: NegotiationSignal[];
  collisions: OfferCollisionInfo[];
  ignoredCandidates: string[];
  errors: string[];
  restartFailures: Array<{ error: string; reason: string }>;
  answersApplied: number;
}

/**
 * Два участника и очередь доставки сигналов между ними.
 * `settle()` прокручивает очередь до пуста — без sleep и без реального времени.
 */
export class Switchboard {
  private readonly endpoints = new Map<string, Endpoint>();
  private queue: Array<() => Promise<void>> = [];

  add(id: string, polite: boolean): Endpoint {
    const peer = new FakePeer(id);
    let negotiator: SessionNegotiator | null = null;
    const endpoint: Endpoint = {
      id,
      polite,
      peer,
      negotiator: negotiator as unknown as SessionNegotiator,
      sent: [],
      collisions: [],
      ignoredCandidates: [],
      errors: [],
      restartFailures: [],
      answersApplied: 0,
    };
    negotiator = new SessionNegotiator(
      peer,
      { polite },
      {
        sendSignal: (payload) => {
          endpoint.sent.push(payload);
          this.enqueueForPeer(id, payload);
        },
        onError: (message) => endpoint.errors.push(message),
        onOfferCollision: (info) => endpoint.collisions.push(info),
        onIgnoredCandidate: (reason) => endpoint.ignoredCandidates.push(reason),
        onIceRestartFailed: (error, reason) => endpoint.restartFailures.push({ error, reason }),
        onAnswerApplied: () => {
          endpoint.answersApplied += 1;
        },
        onRemoteOffer: () => undefined,
      },
    );
    endpoint.negotiator = negotiator;
    this.endpoints.set(id, endpoint);
    return endpoint;
  }

  get(id: string): Endpoint {
    const endpoint = this.endpoints.get(id);
    if (!endpoint) throw new Error(`unknown endpoint ${id}`);
    return endpoint;
  }

  /** Все исходящие сигналы участника заданного типа. */
  offers(id: string): NegotiationSignal[] {
    return this.get(id).sent.filter((signal) => signal.type === 'offer');
  }

  private enqueueForPeer(fromId: string, payload: NegotiationSignal): void {
    const target = [...this.endpoints.keys()].find((id) => id !== fromId);
    if (!target) return;
    const endpoint = this.get(target);
    this.queue.push(() => endpoint.negotiator.handleSignal(payload));
  }

  async settle(limit = 500): Promise<void> {
    let guard = 0;
    while (this.queue.length > 0) {
      guard += 1;
      if (guard > limit) throw new Error('switchboard failed to settle');
      const task = this.queue.shift();
      if (task) await task();
    }
  }

  close(): void {
    for (const endpoint of this.endpoints.values()) endpoint.negotiator.close();
    this.queue = [];
  }
}

/** ufrag предложения/ответа — для проверки отбрасывания «старых» кандидатов. */
export function ufragOf(signal: NegotiationSignal): string | null {
  if (signal.type === 'candidate') return signal.username_fragment ?? null;
  const match = /a=ice-ufrag:([^\r\n]+)/.exec(signal.sdp);
  return match ? match[1].trim() : null;
}
