/** Состояние аутентификации: пользователь, токены, bootstrap сессии. */
import { create } from 'zustand';
import {
  ApiError,
  api,
  clearTokens,
  getAccessToken,
  getRefreshToken,
  hasRefreshToken,
  humanizeApiError,
  rememberEmail,
  setTokens,
} from '../lib/api';
import type { User } from '../lib/types';
import { syncPushToken } from '../lib/push';
import { clearPending, savePending } from '../lib/pending';

export type AuthStatus = 'idle' | 'loading' | 'authenticated' | 'anonymous';

/** Итог входа: пароль верный, но адрес не подтверждён — нужен код из письма. */
export type LoginOutcome = 'ok' | 'unverified' | 'error';

export interface ResendOutcome {
  ok: boolean;
  /** Через сколько секунд можно повторить (0 — сразу). */
  resendIn: number;
  /** Срок жизни нового кода в секундах. */
  expiresIn: number;
  /** Код, продублированный сервером в development (иначе null). */
  devCode: string | null;
  message: string | null;
}

/** Итог запроса кода сброса пароля. */
export interface ForgotOutcome {
  ok: boolean;
  resendIn: number;
  expiresIn: number;
  devCode: string | null;
  message: string | null;
}

/** Итог смены пароля по коду. */
export interface ResetOutcome {
  ok: boolean;
  message: string | null;
}

interface AuthState {
  user: User | null;
  status: AuthStatus;
  error: string | null;
  /** Восстановление сессии при загрузке: /me по сохранённым токенам. */
  bootstrap: () => Promise<void>;
  login: (email: string, password: string, remember?: boolean) => Promise<LoginOutcome>;
  register: (email: string, displayName: string, password: string) => Promise<boolean>;
  /** Подтверждение кода из письма: единственный путь к токенам после register. */
  verifyEmail: (email: string, code: string) => Promise<boolean>;
  resendCode: (email: string) => Promise<ResendOutcome>;
  /** Сброс пароля, шаг 1: запросить код на email. */
  forgotPassword: (email: string) => Promise<ForgotOutcome>;
  /** Сброс пароля, шаг 2: код + новый пароль. */
  resetPassword: (email: string, code: string, password: string) => Promise<ResetOutcome>;
  logout: () => Promise<void>;
  patchMe: (patch: { display_name?: string; avatar_url?: string | null }) => Promise<void>;
  setUser: (user: User) => void;
  forceSignOut: (reason?: string) => void;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  status: 'idle',
  error: null,

  bootstrap: async () => {
    if (get().status === 'loading') return;

    // Токенов нет вообще — сразу аноним, без сетевого запроса.
    if (!hasRefreshToken() && !getAccessToken()) {
      set({ status: 'anonymous', user: null, error: null });
      return;
    }

    set({ status: 'loading', error: null });
    try {
      const { user } = await api.auth.me();
      set({ user, status: 'authenticated', error: null });
      void syncPushToken();
    } catch (error) {
      // 401 уже обработан в api (refresh + notifyAuthFailure), остальное — просто нет сессии.
      if (error instanceof ApiError && error.code === 'network_error') {
        // Сеть недоступна: не выкидываем пользователя, покажем экран логина с ошибкой.
        set({ status: 'anonymous', user: null, error: 'Нет соединения с сервером' });
        return;
      }
      clearTokens();
      set({ status: 'anonymous', user: null, error: null });
    }
  },

  login: async (email, password, remember = true) => {
    set({ error: null });
    try {
      const response = await api.auth.login({ email: email.trim(), password });
      setTokens(response.access_token, response.refresh_token, response.expires_in, remember);
      rememberEmail(email.trim().toLowerCase(), remember);
      set({ user: response.user, status: 'authenticated', error: null });
      void syncPushToken();
      return 'ok';
    } catch (error) {
      set({ error: humanizeApiError(error) });
      const details = error instanceof ApiError ? error.details ?? {} : {};
      return details.reason === 'email_not_verified' ? 'unverified' : 'error';
    }
  },

  register: async (email, displayName, password) => {
    set({ error: null });
    try {
      const response = await api.auth.register({
        email: email.trim(),
        display_name: displayName.trim(),
        password,
      });
      // Токенов нет: аккаунт ждёт код из письма, адрес запоминаем для /verify-email.
      savePending({
        email: email.trim().toLowerCase(),
        resendIn: response.resend_in,
        expiresIn: response.expires_in,
        devCode: response.dev_code ?? null,
      });
      return true;
    } catch (error) {
      set({ error: humanizeApiError(error) });
      return false;
    }
  },

  verifyEmail: async (email, code) => {
    set({ error: null });
    try {
      const response = await api.auth.verify({ email: email.trim(), code });
      // Регистрация только что завершена: сессию сохраняем, как при «Запомнить меня».
      setTokens(response.access_token, response.refresh_token, response.expires_in, true);
      set({ user: response.user, status: 'authenticated', error: null });
      clearPending();
      void syncPushToken();
      return true;
    } catch (error) {
      set({ error: humanizeApiError(error) });
      return false;
    }
  },

  resendCode: async (email) => {
    try {
      const response = await api.auth.resend(email.trim());
      return {
        ok: true,
        resendIn: response.resend_in,
        expiresIn: response.expires_in,
        devCode: response.dev_code ?? null,
        message: null,
      };
    } catch (error) {
      const details = error instanceof ApiError ? error.details ?? {} : {};
      const resendIn = typeof details.resend_in === 'number' ? details.resend_in : 0;
      return { ok: false, resendIn, expiresIn: 0, devCode: null, message: humanizeApiError(error) };
    }
  },

  forgotPassword: async (email) => {
    try {
      const response = await api.auth.forgotPassword(email.trim());
      return {
        ok: true,
        resendIn: response.resend_in,
        expiresIn: response.expires_in,
        devCode: response.dev_code ?? null,
        message: null,
      };
    } catch (error) {
      const details = error instanceof ApiError ? error.details ?? {} : {};
      const resendIn = typeof details.resend_in === 'number' ? details.resend_in : 0;
      return { ok: false, resendIn, expiresIn: 0, devCode: null, message: humanizeApiError(error) };
    }
  },

  resetPassword: async (email, code, password) => {
    try {
      await api.auth.resetPassword({ email: email.trim(), code, password });
      return { ok: true, message: null };
    } catch (error) {
      return { ok: false, message: humanizeApiError(error) };
    }
  },

  logout: async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken) await api.auth.logout(refreshToken);
    clearTokens();
    set({ user: null, status: 'anonymous', error: null });
  },

  patchMe: async (patch) => {
    try {
      const { user } = await api.auth.patchMe(patch);
      set({ user });
    } catch (error) {
      set({ error: humanizeApiError(error) });
    }
  },

  setUser: (user) => set({ user }),

  /** Вызывается извне (невалидный refresh, logout по 401). */
  forceSignOut: (reason) => {
    clearTokens();
    set({ user: null, status: 'anonymous', error: reason ?? null });
  },

  clearError: () => set({ error: null }),
}));
