import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CodeInput } from '../components/auth/CodeInput';
import { IconSpinner } from '../components/Icons';
import { useAuthStore } from '../store/auth';
import { readRememberedEmail } from '../lib/api';
import { toast } from '../store/toast';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_LENGTH = 6;
const MIN_PASSWORD = 8;

type Phase = 'email' | 'reset';

/**
 * Сброс пароля: адрес → код из письма + новый пароль.
 *
 * <p>Ответ на запрос кода не раскрывает, зарегистрирован ли адрес: сервер
 * отвечает одинаково, поэтому и текст нейтральный («если адрес существует»).
 */
export function ForgotPasswordPage() {
  const navigate = useNavigate();
  const forgotPassword = useAuthStore((state) => state.forgotPassword);
  const resetPassword = useAuthStore((state) => state.resetPassword);

  const [phase, setPhase] = useState<Phase>('email');
  // Если вход уже запоминали, адрес подставлен — останется только получить код.
  const [email, setEmail] = useState(() => readRememberedEmail());
  const [emailError, setEmailError] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [noticeKind, setNoticeKind] = useState<'info' | 'success'>('info');
  const [submitting, setSubmitting] = useState(false);
  const [resendLeft, setResendLeft] = useState(0);
  const [expiresLeft, setExpiresLeft] = useState(0);
  const [devCode, setDevCode] = useState<string | null>(null);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setResendLeft((value) => (value > 0 ? value - 1 : 0));
      setExpiresLeft((value) => (value > 0 ? value - 1 : 0));
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const requestCode = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    const value = email.trim().toLowerCase();
    if (!EMAIL_RE.test(value)) {
      setEmailError('Похоже, email указан неверно');
      return;
    }
    setEmailError(null);
    setNotice(null);
    setSubmitting(true);
    const outcome = await forgotPassword(value);
    setSubmitting(false);

    setResendLeft(Math.max(0, outcome.resendIn));
    setExpiresLeft(Math.max(0, outcome.expiresIn));
    setDevCode(outcome.devCode);
    if (!outcome.ok) {
      setNoticeKind('info');
      setNotice(outcome.message ?? 'Не удалось отправить код');
      return;
    }
    setPhase('reset');
    setNoticeKind('success');
    setNotice('Если адрес зарегистрирован, письмо с кодом уже отправлено');
  };

  const submitReset = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    const digits = code.replace(/\D/g, '');
    if (digits.length !== CODE_LENGTH) {
      setNoticeKind('info');
      setNotice(`Введите все ${CODE_LENGTH} цифр из письма`);
      return;
    }
    if (password.length < MIN_PASSWORD) {
      setPasswordError(`Пароль должен быть не короче ${MIN_PASSWORD} символов`);
      return;
    }
    if (password !== confirm) {
      setPasswordError('Пароли не совпадают');
      return;
    }
    setPasswordError(null);
    setNotice(null);
    setSubmitting(true);
    const outcome = await resetPassword(email.trim().toLowerCase(), digits, password);
    setSubmitting(false);
    if (!outcome.ok) {
      setNoticeKind('info');
      setNotice(outcome.message ?? 'Не удалось сменить пароль');
      return;
    }
    toast.success('Пароль обновлён', 'Войдите с новым паролем');
    // Адрес передаём форме входа: после сброса не нужно вводить его заново.
    navigate('/login', { replace: true, state: { email: email.trim().toLowerCase() } });
  };

  const resendCode = async (): Promise<void> => {
    if (submitting || resendLeft > 0) return;
    setSubmitting(true);
    const outcome = await forgotPassword(email.trim().toLowerCase());
    setSubmitting(false);
    setResendLeft(Math.max(0, outcome.resendIn));
    setExpiresLeft(Math.max(0, outcome.expiresIn));
    setDevCode(outcome.devCode);
    setNoticeKind(outcome.ok ? 'success' : 'info');
    setNotice(outcome.ok ? 'Код отправлен ещё раз' : outcome.message ?? 'Не удалось отправить код');
  };

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={phase === 'email' ? requestCode : submitReset} noValidate>
        <h1 className="auth__title">Сброс пароля</h1>
        <p className="auth__subtitle">
          {phase === 'email'
            ? 'Пришлём код на почту, привязанную к аккаунту'
            : `Код и новый пароль для ${email}`}
        </p>

        {notice ? (
          <p className={`auth__info${noticeKind === 'success' ? ' auth__info--success' : ''}`}>{notice}</p>
        ) : null}

        {phase === 'email' ? (
          <>
            <label className="field">
              <span className="field__label">Email</span>
              <input
                className={`field__input${emailError ? ' field__input--invalid' : ''}`}
                type="email"
                value={email}
                autoComplete="email"
                placeholder="you@example.com"
                onChange={(event) => {
                  setEmail(event.target.value);
                  setEmailError(null);
                }}
              />
              {emailError ? <span className="field__hint field__hint--error">{emailError}</span> : null}
            </label>

            <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
              {submitting ? <IconSpinner size={18} /> : null}
              Отправить код
            </button>
          </>
        ) : (
          <>
            <label className="field">
              <span className="field__label">Код из письма</span>
              <CodeInput
                value={code}
                length={CODE_LENGTH}
                autoFocus
                disabled={submitting}
                onChange={setCode}
              />
            </label>

            {devCode ? (
              <p className="auth__info">development: код {devCode}</p>
            ) : null}

            {expiresLeft > 0 ? (
              <p className="verify__hint">Код действует ещё {formatSeconds(expiresLeft)}</p>
            ) : null}

            <label className="field">
              <span className="field__label">Новый пароль</span>
              <input
                className={`field__input${passwordError ? ' field__input--invalid' : ''}`}
                type="password"
                value={password}
                autoComplete="new-password"
                placeholder="••••••••"
                onChange={(event) => {
                  setPassword(event.target.value);
                  setPasswordError(null);
                }}
              />
            </label>

            <label className="field">
              <span className="field__label">Повторите пароль</span>
              <input
                className="field__input"
                type="password"
                value={confirm}
                autoComplete="new-password"
                placeholder="••••••••"
                onChange={(event) => {
                  setConfirm(event.target.value);
                  setPasswordError(null);
                }}
              />
              {passwordError ? <span className="field__hint field__hint--error">{passwordError}</span> : null}
            </label>

            <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
              {submitting ? <IconSpinner size={18} /> : null}
              Сменить пароль
            </button>

            <button
              type="button"
              className="btn btn--ghost btn--block"
              onClick={() => {
                setPhase('email');
                setCode('');
                setPassword('');
                setConfirm('');
                setNotice(null);
              }}
              disabled={submitting}
            >
              Изменить адрес
            </button>

            <button
              type="button"
              className="btn btn--ghost btn--block"
              onClick={() => void resendCode()}
              disabled={submitting || resendLeft > 0}
            >
              {resendLeft > 0 ? `Отправить повторно через ${resendLeft} с` : 'Отправить код повторно'}
            </button>
          </>
        )}

        <p className="auth__footer">
          Вспомнили пароль? <Link to="/login">Войти</Link>
        </p>
      </form>
    </div>
  );
}

function formatSeconds(total: number): string {
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
