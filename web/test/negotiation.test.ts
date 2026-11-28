/**
 * Perfect negotiation: коллизия offer'ов, ICE restart (в т.ч. одновременный
 * у обоих peers), отбрасывание кандидатов от проигравшего offer'а.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Switchboard, ufragOf } from './harness.ts';

/** Начальное соединение: impolite создаёт offer, polite отвечает answer. */
async function establish(): Promise<{ board: Switchboard; impolite: ReturnType<Switchboard['add']>; polite: ReturnType<Switchboard['add']> }> {
  const board = new Switchboard();
  const impolite = board.add('impolite', false);
  const polite = board.add('polite', true);
  await impolite.negotiator.negotiationNeeded();
  await board.settle();

  assert.equal(impolite.peer.remoteDescription?.type, 'answer', 'звонящий получил answer');
  assert.equal(polite.peer.remoteDescription?.type, 'offer', 'вызываемый получил offer');
  assert.equal(impolite.peer.signalingState, 'stable');
  assert.equal(polite.peer.signalingState, 'stable');
  return { board, impolite, polite };
}

test('успешный ICE restart: новый offer/answer, ufrag меняется, restart не висит', async () => {
  const { board, impolite, polite } = await establish();
  const offerBefore = board.offers('impolite')[0];
  const ufragBefore = offerBefore ? ufragOf(offerBefore) : null;
  const remoteSdpBefore = polite.peer.remoteDescription?.sdp;

  impolite.negotiator.requestIceRestart('manual_restart');
  await impolite.negotiator.negotiationNeeded();
  await board.settle();

  assert.equal(impolite.peer.restartIceCalls, 1, 'restartIce вызван ровно один раз');
  assert.equal(board.offers('impolite').length, 2, 'отправлен второй offer');
  assert.equal(impolite.peer.remoteDescription?.type, 'answer');
  assert.equal(polite.peer.remoteDescription?.type, 'offer');
  assert.notEqual(polite.peer.remoteDescription?.sdp, remoteSdpBefore, 'удалённое описание обновилось');

  const offerAfter = board.offers('impolite')[1];
  assert.ok(offerAfter);
  assert.notEqual(ufragOf(offerAfter), ufragBefore, 'ICE-креды обновились');
  assert.equal(impolite.negotiator.isRestartInFlight(), false, 'restart завершён ответом');
  assert.equal(impolite.negotiator.getRestartReason(), null);
  assert.deepEqual(impolite.errors, []);
  assert.deepEqual(polite.errors, []);
  assert.deepEqual(impolite.collisions, [], 'коллизии не было');
  board.close();
});

test('одновременный ICE restart обоих peers: impolite игнорирует, polite откатывает', async () => {
  const { board, impolite, polite } = await establish();

  impolite.negotiator.requestIceRestart('disconnected_timeout');
  polite.negotiator.requestIceRestart('disconnected_timeout');
  // Браузер после restartIce() шлёт negotiationneeded асинхронно — эмулируем.
  await Promise.all([
    impolite.negotiator.negotiationNeeded(),
    polite.negotiator.negotiationNeeded(),
  ]);
  await board.settle();

  assert.equal(impolite.peer.restartIceCalls, 1);
  assert.equal(polite.peer.restartIceCalls, 1);
  assert.equal(polite.peer.rollbackCalls, 1, 'polite откатил свой offer');

  assert.deepEqual(
    impolite.collisions.map((item) => item.action),
    ['ignored'],
    'impolite игнорирует встречный offer',
  );
  assert.deepEqual(
    polite.collisions.map((item) => item.action),
    ['rollback'],
    'polite откатывает свой offer',
  );

  assert.equal(board.offers('polite').length, 1, 'offer polite был отправлен и проигнорирован');
  assert.equal(board.offers('impolite').length, 2, 'первый offer + restart-offer');
  assert.equal(impolite.peer.signalingState, 'stable');
  assert.equal(polite.peer.signalingState, 'stable');
  assert.equal(impolite.peer.remoteDescription?.type, 'answer');
  assert.equal(polite.peer.remoteDescription?.type, 'offer');
  assert.equal(impolite.negotiator.isRestartInFlight(), false);
  assert.equal(polite.negotiator.isRestartInFlight(), false);
  assert.deepEqual(impolite.errors, []);
  assert.deepEqual(polite.errors, []);
  board.close();
});

test('кандидаты от проигнорированного offer\'а не применяются', async () => {
  const { board, impolite, polite } = await establish();

  impolite.negotiator.requestIceRestart('disconnected_timeout');
  polite.negotiator.requestIceRestart('disconnected_timeout');
  await Promise.all([
    impolite.negotiator.negotiationNeeded(),
    polite.negotiator.negotiationNeeded(),
  ]);
  await board.settle();

  const ignoredOffer = board.offers('polite')[0];
  assert.ok(ignoredOffer);
  const ignoredUfrag = ufragOf(ignoredOffer);
  assert.ok(ignoredUfrag);

  await impolite.negotiator.handleSignal({
    type: 'candidate',
    candidate: 'candidate:1 1 UDP 2122260223 10.0.0.1 5000 typ host',
    sdp_mid: '0',
    sdp_mline_index: 0,
    username_fragment: ignoredUfrag,
  });

  assert.ok(impolite.ignoredCandidates.includes('ignored_offer'));
  assert.equal(
    impolite.peer.candidates.length,
    0,
    'кандидат от проигравшего offer\'а не попал в RTCPeerConnection',
  );
  board.close();
});

test('кандидаты буферизуются до remote description и применяются после offer', async () => {
  const board = new Switchboard();
  const impolite = board.add('impolite', false);
  const polite = board.add('polite', true);

  await impolite.negotiator.handleSignal({
    type: 'candidate',
    candidate: 'candidate:2 1 UDP 2122260223 10.0.0.2 6000 typ host',
    sdp_mid: '0',
    sdp_mline_index: 0,
    username_fragment: null,
  });
  assert.ok(impolite.ignoredCandidates.includes('remote_description_missing'));
  assert.equal(impolite.peer.candidates.length, 0);

  await impolite.negotiator.negotiationNeeded();
  await board.settle();

  assert.equal(impolite.peer.candidates.length, 1, 'буферизованный кандидат применён');
  board.close();
});

test('кандидат со старым ufrag после restart отбрасывается', async () => {
  const { board, impolite } = await establish();
  const initialOffer = board.offers('impolite')[0];
  assert.ok(initialOffer);
  const staleUfrag = ufragOf(initialOffer);

  impolite.negotiator.requestIceRestart('manual_restart');
  await impolite.negotiator.negotiationNeeded();
  await board.settle();

  await impolite.negotiator.handleSignal({
    type: 'candidate',
    candidate: 'candidate:3 1 UDP 2122260223 10.0.0.3 7000 typ host',
    sdp_mid: '0',
    sdp_mline_index: 0,
    username_fragment: staleUfrag,
  });

  assert.ok(impolite.ignoredCandidates.includes('stale_ufrag'));
  assert.equal(impolite.peer.candidates.length, 0);
  board.close();
});

test('restartIce() бросил исключение: попытка сообщается наружу, флаг не залипает', async () => {
  const { board, impolite } = await establish();
  impolite.peer.failRestartIce = 'restartIce is not allowed in this state';

  impolite.negotiator.requestIceRestart('connection_failed');

  assert.deepEqual(impolite.restartFailures, [
    { error: 'restartIce is not allowed in this state', reason: 'connection_failed' },
  ]);
  assert.equal(impolite.negotiator.isRestartInFlight(), false);
  board.close();
});

test('answer без локального offer игнорируется, а не роняет очередь', async () => {
  const { board, impolite, polite } = await establish();
  const before = impolite.peer.setRemoteDescriptionCalls;

  await impolite.negotiator.handleSignal({ type: 'answer', sdp: 'v=0\r\na=ice-ufrag:stale\r\n' });

  assert.equal(impolite.peer.setRemoteDescriptionCalls, before, 'stale answer не применён');
  assert.deepEqual(impolite.errors, []);

  await polite.negotiator.handleSignal({ type: 'candidate', candidate: 'candidate:9 1 UDP 1 10.0.0.9 9000 typ host', sdp_mid: '0', sdp_mline_index: 0, username_fragment: null });
  await board.settle();
  assert.deepEqual(impolite.errors, []);
  board.close();
});
