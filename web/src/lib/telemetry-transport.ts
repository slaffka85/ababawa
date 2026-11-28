/**
 * Транспорт клиентской телеметрии: batch уходит одним POST на backend
 * (`POST /api/v1/calls/telemetry`), который валидирует, обогащает и пишет
 * события в stdout в формате NDJSON.
 *
 * Модуль сознательно тонкий: очередь, backpressure и retry живут в
 * CallTelemetry (telemetry.ts), а `api.request` отвечает за авторизацию.
 */
import { api } from './api';
import type { CallTelemetryEvent } from './telemetry';

/** Отправляет batch. Ошибка пробрасывается — CallTelemetry решает про retry. */
export async function postCallTelemetry(events: CallTelemetryEvent[]): Promise<void> {
  if (events.length === 0) return;
  await api.calls.telemetry(events);
}
