/**
 * Perfect negotiation для 1:1 звонка.
 *
 * Модуль сознательно не создаёт второй signaling: он работает поверх того же
 * `call.signal`, что и раньше, и добавляет только необязательный
 * `username_fragment` к ICE-кандидатам, чтобы отбрасывать кандидатов от
 * «проигравшего» offer'а.
 *
 * Реализовано:
 *  - offer collision: impolite игнорирует встречный offer, polite откатывает
 *    свой (`rollback`) и принимает входящий;
 *  - кандидаты буферизуются до remote description; кандидаты из игнорируемых
 *    offer'ов не применяются;
 *  - ICE restart и renegotiation сериализованы: одновременно в полёте не
 *    больше одного offer'а.
 */

export type NegotiationSignal =
  | { type: 'offer' | 'answer'; sdp: string; username_fragment?: string | null }
  | {
      type: 'candidate';
      candidate: string;
      sdp_mid: string | null;
      sdp_mline_index: number | null;
      username_fragment?: string | null;
    };

export interface SessionDescriptionLike {
  type: 'offer' | 'answer' | 'pranswer' | 'rollback';
  sdp?: string;
}

export interface IceCandidateLike {
  candidate: string;
  sdpMid?: string | null;
  sdpMLineIndex?: number | null;
  usernameFragment?: string | null;
}

/** Минимальный контракт RTCPeerConnection, который нужен negotiation-слою. */
export interface NegotiationPeer {
  readonly signalingState: string;
  readonly remoteDescription: SessionDescriptionLike | null;
  setLocalDescription(description?: SessionDescriptionLike): Promise<void>;
  setRemoteDescription(description: SessionDescriptionLike): Promise<void>;
  addIceCandidate(candidate: IceCandidateLike): Promise<void>;
  createAnswer(): Promise<SessionDescriptionLike>;
  restartIce(): void;
}

export interface OfferCollisionInfo {
  polite: boolean;
  /** rollback — polite откатил свой offer; ignored — impolite пропустил входящий. */
  action: 'rollback' | 'ignored';
  remoteUfrag: string | null;
}

export interface NegotiationCallbacks {
  sendSignal: (payload: NegotiationSignal) => void;
  onError: (message: string) => void;
  /** Перед answer: вызываемый добавляет свои треки в transceiver'ы из offer'а. */
  onRemoteOffer?: () => Promise<void> | void;
  /** Применён ответ на наш offer (в т.ч. завершение ICE restart). */
  onAnswerApplied?: () => void;
  onOfferCollision?: (info: OfferCollisionInfo) => void;
  /**
   * restartIce() бросил исключение — восстановление должно учесть неудачу.
   * `reason` — причина, с которой restart был запрошен (см. requestIceRestart).
   */
  onIceRestartFailed?: (error: string, reason: string) => void;
  onIgnoredCandidate?: (reason: 'ignored_offer' | 'stale_ufrag' | 'remote_description_missing') => void;
}

export interface NegotiationOptions {
  polite: boolean;
  /** Предел буфера кандидатов до remote description (защита от утечки памяти). */
  maxPendingCandidates?: number;
}

const DEFAULT_MAX_PENDING_CANDIDATES = 100;

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

/** Первый `a=ice-ufrag:` из SDP; при BUNDLE он общий для всех m-line. */
export function extractIceUfrag(sdp: string | null | undefined): string | null {
  if (!sdp) return null;
  const match = /a=ice-ufrag:([^\r\n]+)/.exec(sdp);
  return match ? match[1].trim() : null;
}

export class SessionNegotiator {
  private readonly peer: NegotiationPeer;
  private readonly polite: boolean;
  private readonly callbacks: NegotiationCallbacks;
  private readonly maxPendingCandidates: number;

  private makingOffer = false;
  private ignoreOffer = false;
  private isSettingRemoteAnswerPending = false;
  private closed = false;

  private pendingCandidates: IceCandidateLike[] = [];
  private ignoredUfrags = new Set<string>();
  private remoteUfrag: string | null = null;

  private pendingIceRestartReason: string | null = null;
  private pendingNegotiation = false;
  private restartInFlight = false;
  /** Причина, с которой запрошен текущий restart (для телеметрии и ошибок). */
  private restartReason: string | null = null;

  /** Все операции с signalingState выполняются строго последовательно. */
  private queue: Promise<void> = Promise.resolve();

  constructor(peer: NegotiationPeer, options: NegotiationOptions, callbacks: NegotiationCallbacks) {
    this.peer = peer;
    this.polite = options.polite;
    this.callbacks = callbacks;
    this.maxPendingCandidates = options.maxPendingCandidates ?? DEFAULT_MAX_PENDING_CANDIDATES;
  }

  isPolite(): boolean {
    return this.polite;
  }

  isClosed(): boolean {
    return this.closed;
  }

  isRestartInFlight(): boolean {
    return this.restartInFlight;
  }

  /** Причина текущего запрошенного ICE restart (null — restart не запрашивался). */
  getRestartReason(): string | null {
    return this.restartReason;
  }

  hasPendingRestart(): boolean {
    return this.pendingIceRestartReason !== null;
  }

  getRemoteIceUfrag(): string | null {
    return this.remoteUfrag;
  }

  /** Обработка call.signal (offer/answer/candidate). */
  handleSignal(payload: NegotiationSignal): Promise<void> {
    if (this.closed) return Promise.resolve();
    return this.enqueue(() => this.applySignal(payload));
  }

  /** Событие onnegotiationneeded: локальные треки/restartIce требуют offer. */
  negotiationNeeded(): Promise<void> {
    if (this.closed) return Promise.resolve();
    return this.enqueue(() => this.runNegotiation());
  }

  /**
   * Запрос ICE restart. Если уже идёт negotiation, запрос ставится в очередь
   * (не выполняется параллельно) и будет запущен, когда signalingState станет
   * stable.
   */
  requestIceRestart(reason: string): void {
    if (this.closed) return;
    if (this.restartInFlight || this.makingOffer || this.peer.signalingState !== 'stable') {
      // Один pending restart: повторные запросы схлопываются.
      this.pendingIceRestartReason ??= reason;
      return;
    }
    this.startRestart(reason);
  }

  /** Восстановление неотвеченного offer'а (вызываемый был офлайн). */
  async recoverUnansweredOffer(reason = 'recover_unanswered_offer'): Promise<void> {
    if (this.closed || this.peer.remoteDescription) return;
    await this.enqueue(async () => {
      if (this.closed || this.peer.remoteDescription) return;
      if (this.peer.signalingState === 'have-local-offer') {
        await this.peer.setLocalDescription({ type: 'rollback' });
      }
      this.requestIceRestart(reason);
    });
  }

  /** Завершение звонка: очередь больше не обрабатывается, буферы очищаются. */
  close(): void {
    this.closed = true;
    this.pendingCandidates = [];
    this.ignoredUfrags.clear();
    this.pendingIceRestartReason = null;
    this.pendingNegotiation = false;
    this.restartInFlight = false;
    this.restartReason = null;
    this.ignoreOffer = false;
    this.makingOffer = false;
    this.isSettingRemoteAnswerPending = false;
  }

  /* ------------------------------------------------------------------ */

  private enqueue(task: () => Promise<void>): Promise<void> {
    const run = this.queue.then(task, task).catch((error) => {
      if (!this.closed) this.callbacks.onError(errorMessage(error));
    });
    this.queue = run;
    return run;
  }

  private async applySignal(payload: NegotiationSignal): Promise<void> {
    if (this.closed) return;
    if (payload.type === 'candidate') {
      await this.applyCandidate(payload);
      return;
    }

    const ufrag = extractIceUfrag(payload.sdp) ?? payload.username_fragment ?? null;
    const description: SessionDescriptionLike = { type: payload.type, sdp: payload.sdp };

    // Ответ, которого мы не ждём (например, после rollback из-за коллизии или
    // пришедший дважды), нельзя применять: setRemoteDescription бросит
    // InvalidStateError и сорвёт очередь согласования.
    if (payload.type === 'answer' && this.peer.signalingState !== 'have-local-offer') {
      return;
    }

    const readyForOffer =
      !this.makingOffer && (this.peer.signalingState === 'stable' || this.isSettingRemoteAnswerPending);
    const offerCollision = payload.type === 'offer' && !readyForOffer;

    this.ignoreOffer = !this.polite && offerCollision;
    if (this.ignoreOffer) {
      if (ufrag) this.ignoredUfrags.add(ufrag);
      this.callbacks.onOfferCollision?.({ polite: this.polite, action: 'ignored', remoteUfrag: ufrag });
      return;
    }

    if (offerCollision && this.polite && this.peer.signalingState === 'have-local-offer') {
      await this.peer.setLocalDescription({ type: 'rollback' });
      // Оффер, который мы только что откатили, мог породить restart; снимаем
      // флаг, иначе следующий negotiation будет считать restart всё ещё в полёте.
      this.restartInFlight = false;
      this.restartReason = null;
      this.callbacks.onOfferCollision?.({ polite: this.polite, action: 'rollback', remoteUfrag: ufrag });
    }

    this.isSettingRemoteAnswerPending = payload.type === 'answer';
    try {
      await this.peer.setRemoteDescription(description);
    } catch (error) {
      this.isSettingRemoteAnswerPending = false;
      throw error;
    }
    this.isSettingRemoteAnswerPending = false;
    // Принятое описание делает свой ufrag актуальным: кандидаты с ним больше
    // не считаются «от проигравшего offer'а» (браузер может переиспользовать
    // ufrag между offer и answer одной renegotiation).
    if (ufrag) this.ignoredUfrags.delete(ufrag);
    this.remoteUfrag = ufrag ?? this.remoteUfrag;
    // Описание применено успешно — этот сигнал больше не игнорируется.
    this.ignoreOffer = false;

    if (payload.type === 'offer') {
      await this.callbacks.onRemoteOffer?.();
      const answer = await this.peer.createAnswer();
      await this.peer.setLocalDescription(answer);
      const local = (this.peer as { localDescription?: SessionDescriptionLike | null }).localDescription;
      if (local?.sdp) {
        this.callbacks.sendSignal({
          type: 'answer',
          sdp: local.sdp,
          username_fragment: extractIceUfrag(local.sdp),
        });
      }
    } else {
      this.restartInFlight = false;
      this.restartReason = null;
      this.callbacks.onAnswerApplied?.();
    }

    await this.flushPendingCandidates();
    if (!this.closed) await this.drainPending();
  }

  private async applyCandidate(candidate: NegotiationSignal & { type: 'candidate' }): Promise<void> {
    const ufrag = candidate.username_fragment ?? null;
    if (ufrag && this.ignoredUfrags.has(ufrag)) {
      this.callbacks.onIgnoredCandidate?.('ignored_offer');
      return;
    }

    const init: IceCandidateLike = {
      candidate: candidate.candidate,
      sdpMid: candidate.sdp_mid,
      sdpMLineIndex: candidate.sdp_mline_index,
      usernameFragment: ufrag,
    };

    if (!this.peer.remoteDescription) {
      if (this.pendingCandidates.length >= this.maxPendingCandidates) {
        // Старые кандидаты бесполезны: ICE-креды меняются при restart.
        this.pendingCandidates.shift();
      }
      this.pendingCandidates.push(init);
      this.callbacks.onIgnoredCandidate?.('remote_description_missing');
      return;
    }

    if (this.remoteUfrag && ufrag && ufrag !== this.remoteUfrag) {
      // Кандидат относится к откатанному/игнорированному offer'у.
      this.callbacks.onIgnoredCandidate?.('stale_ufrag');
      return;
    }

    try {
      await this.peer.addIceCandidate(init);
    } catch (error) {
      if (!this.ignoreOffer) throw error;
    }
  }

  private async runNegotiation(): Promise<void> {
    if (this.closed) return;
    if (this.makingOffer || this.peer.signalingState !== 'stable') {
      this.pendingNegotiation = true;
      return;
    }

    this.makingOffer = true;
    try {
      await this.peer.setLocalDescription();
      const local = (this.peer as { localDescription?: SessionDescriptionLike | null }).localDescription;
      if (local?.sdp) {
        this.callbacks.sendSignal({
          type: 'offer',
          sdp: local.sdp,
          username_fragment: extractIceUfrag(local.sdp),
        });
      }
    } finally {
      this.makingOffer = false;
    }

    await this.drainPending();
  }

  private startRestart(reason: string): void {
    if (this.closed) return;
    this.pendingIceRestartReason = null;
    this.restartInFlight = true;
    this.restartReason = reason;
    try {
      this.peer.restartIce();
    } catch (error) {
      const message = errorMessage(error);
      this.restartInFlight = false;
      this.callbacks.onIceRestartFailed?.(message, reason);
    }
  }

  private async drainPending(): Promise<void> {
    if (this.closed) return;
    if (this.makingOffer || this.peer.signalingState !== 'stable') return;

    if (this.pendingIceRestartReason !== null && !this.restartInFlight) {
      const reason = this.pendingIceRestartReason;
      this.pendingIceRestartReason = null;
      this.startRestart(reason);
      return;
    }

    if (this.pendingNegotiation) {
      this.pendingNegotiation = false;
      await this.runNegotiation();
    }
  }

  private async flushPendingCandidates(): Promise<void> {
    if (this.pendingCandidates.length === 0) return;
    const queued = this.pendingCandidates;
    this.pendingCandidates = [];
    for (const candidate of queued) {
      if (this.closed) return;
      if (this.remoteUfrag && candidate.usernameFragment && candidate.usernameFragment !== this.remoteUfrag) {
        this.callbacks.onIgnoredCandidate?.('stale_ufrag');
        continue;
      }
      try {
        await this.peer.addIceCandidate(candidate);
      } catch (error) {
        if (!this.ignoreOffer) throw error;
      }
    }
  }
}
