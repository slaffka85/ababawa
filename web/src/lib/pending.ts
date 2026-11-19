/**
 * Черновик подтверждения email: адрес и таймеры переживают перезагрузку
 * страницы (state роутера — нет, а email нужен для /auth/verify).
 */
const KEY = 'abv.verify';

export interface PendingVerification {
  email: string;
  /** Через сколько секунд разрешена повторная отправка (0 — можно сразу). */
  resendIn: number;
  /** Срок жизни кода в секундах (0 — неизвестен). */
  expiresIn: number;
  /** Код, продублированный сервером в development (в production всегда null). */
  devCode?: string | null;
}

export function savePending(value: PendingVerification): void {
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(value));
  } catch {
    // приватный режим или запрет storage: подтверждение работает и без черновика
  }
}

export function readPending(): PendingVerification | null {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<PendingVerification>;
    if (typeof parsed.email !== 'string' || !parsed.email) return null;
    return {
      email: parsed.email,
      resendIn: typeof parsed.resendIn === 'number' ? parsed.resendIn : 0,
      expiresIn: typeof parsed.expiresIn === 'number' ? parsed.expiresIn : 0,
      devCode: typeof parsed.devCode === 'string' ? parsed.devCode : null,
    };
  } catch {
    return null;
  }
}

export function clearPending(): void {
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // нечего чистить
  }
}
