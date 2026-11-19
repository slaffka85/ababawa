import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { CodeInput } from '../components/auth/CodeInput';
import { IconSpinner } from '../components/Icons';
import { copyToClipboard, readClipboard } from '../lib/clipboard';
import { readPending, savePending } from '../lib/pending';
import { useAuthStore } from '../store/auth';

const CODE_LENGTH = 6;
const COPIED_NOTICE = 'Код скопирован в буфер обмена';

interface LocationState {
  email?: string;
  resendIn?: number;
  expiresIn?: number;
}

/** `a***@example.com` — показываем адрес, но не целиком. */
function maskEmail(email: string): string {
  const at = email.indexOf('@');
  if (at <= 0) return email;
  return `${email.slice(0, 1)}***${email.slice(at)}`;
}

function formatSeconds(total: number): string {
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/**
 * Экран подтверждения email.
 *
 * <p>Код приходит письмом; если человек открыл письмо и нажал там «Copy code»,
 * страница открывается со `#c=123456` — код сразу подставляется и копируется
 * в буфер обмена. Кнопка «Вставить из буфера» выручает, когда код скопирован
 * из почтового клиента вручную.
 */
export function VerifyEmailPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state ?? {}) as LocationState;
  const stored = useMemo(() => readPending(), []);

  const verifyEmail = useAuthStore((store) => store.verifyEmail);
  const resendCode = useAuthStore((store) => store.resendCode);
  const error = useAuthStore((store) => store.error);
  const clearError = useAuthStore((store) => store.clearError);

  const [email] = useState(() => (state.email ?? stored?.email ?? '').trim());
  const [code, setCode] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [noticeKind, setNoticeKind] = useState<'info' | 'success'>('info');
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);
  const [resendLeft, setResendLeft] = useState(() =>
    Math.max(0, Math.round(state.resendIn ?? stored?.resendIn ?? 0)),
  );
  const [expiresLeft, setExpiresLeft] = useState(() =>
    Math.max(0, Math.round(state.expiresIn ?? stored?.expiresIn ?? 0)),
  );
  const [devCode, setDevCode] = useState<string | null>(stored?.devCode ?? null);
  const hashHandled = useRef(false);

  useEffect(() => {
    if (!email) navigate('/register', { replace: true });
  }, [email, navigate]);

  // Код из ссылки в письме: подставляем и копируем в буфер обмена.
  useEffect(() => {
    if (hashHandled.current) return;
    hashHandled.current = true;
    const params = new URLSearchParams(location.hash.replace(/^#/, ''));
    const fromLetter = (params.get('c') ?? '').replace(/\D/g, '').slice(0, CODE_LENGTH);
    if (fromLetter.length !== CODE_LENGTH) return;
    setCode(fromLetter);
    // Убираем код из адресной строки: он уже в поле ввода.
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`);
    void copyToClipboard(fromLetter).then((copied) => {
      setNoticeKind(copied ? 'success' : 'info');
      setNotice(
        copied
          ? COPIED_NOTICE
          : 'Код из письма подставлен, но браузер не разрешил доступ к буферу обмена',
      );
    });
  }, [location.hash]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setResendLeft((value) => (value > 0 ? value - 1 : 0));
      setExpiresLeft((value) => (value > 0 ? value - 1 : 0));
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const submit = useCallback(async (): Promise<void> => {
    const value = code.replace(/\D/g, '');
    if (value.length !== CODE_LENGTH) {
      setNoticeKind('info');
      setNotice(`Введите все ${CODE_LENGTH} цифр из письма`);
      return;
    }
    setNotice(null);
    setSubmitting(true);
    const ok = await verifyEmail(email, value);
    setSubmitting(false);
    if (ok) navigate('/', { replace: true });
  }, [code, email, navigate, verifyEmail]);

  const handleSubmit = (event: FormEvent): void => {
    event.preventDefault();
    void submit();
  };

  const pasteFromClipboard = async (): Promise<void> => {
    const text = await readClipboard();
    const digits = (text ?? '').replace(/\D/g, '').slice(0, CODE_LENGTH);
    if (!digits) {
      setNoticeKind('info');
      setNotice('Браузер не дал доступ к буферу — вставьте код в поле (Ctrl+V).');
      return;
    }
    setCode(digits);
    if (error) clearError();
    setNoticeKind('success');
    setNotice('Код вставлен из буфера обмена');
  };

  const resend = async (): Promise<void> => {
    setResending(true);
    const result = await resendCode(email);
    setResending(false);
    setResendLeft(Math.max(0, Math.round(result.resendIn)));
    if (!result.ok) {
      setNoticeKind('info');
      if (result.message) setNotice(result.message);
      return;
    }
    if (result.expiresIn > 0) setExpiresLeft(Math.round(result.expiresIn));
    if (result.devCode) setDevCode(result.devCode);
    savePending({
      email,
      resendIn: result.resendIn,
      expiresIn: result.expiresIn,
      devCode: result.devCode ?? devCode,
    });
    setNoticeKind('success');
    setNotice('Письмо отправлено — проверьте почту');
  };

  const copyDevCode = async (value: string): Promise<void> => {
    const copied = await copyToClipboard(value);
    setNoticeKind(copied ? 'success' : 'info');
    setNotice(copied ? COPIED_NOTICE : 'Не удалось скопировать код');
  };

  if (!email) return null;

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={handleSubmit} noValidate>
        <h1 className="auth__title">Подтвердите почту</h1>
        <p className="auth__subtitle">
          Мы отправили код на <span className="verify__email">{maskEmail(email)}</span>
        </p>

        {error ? <p className="auth__error">{error}</p> : null}
        {notice ? (
          <p className={`auth__info${noticeKind === 'success' ? ' auth__info--success' : ''}`}>{notice}</p>
        ) : null}

        <CodeInput
          value={code}
          length={CODE_LENGTH}
          autoFocus
          disabled={submitting}
          invalid={Boolean(error)}
          onChange={(next) => {
            setCode(next);
            if (error) clearError();
          }}
        />

        {expiresLeft > 0 ? (
          <p className="verify__hint">Код действует ещё {formatSeconds(expiresLeft)}</p>
        ) : null}

        {devCode ? (
          <p className="verify__hint">
            development: код{' '}
            <code className="verify__code">{devCode}</code>{' '}
            <button
              type="button"
              className="btn btn--tiny"
              onClick={() => void copyDevCode(devCode)}
            >
              Скопировать
            </button>
          </p>
        ) : null}

        <div className="verify__row">
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => void pasteFromClipboard()}
            disabled={submitting}
          >
            Вставить из буфера
          </button>
          <button
            type="submit"
            className="btn btn--primary"
            disabled={submitting || code.replace(/\D/g, '').length !== CODE_LENGTH}
          >
            {submitting ? <IconSpinner size={18} /> : null}
            Подтвердить
          </button>
        </div>

        <button
          type="button"
          className="btn btn--ghost btn--block"
          onClick={() => void resend()}
          disabled={resending || resendLeft > 0}
        >
          {resending ? <IconSpinner size={16} /> : null}
          {resendLeft > 0 ? `Отправить повторно через ${resendLeft} с` : 'Отправить код повторно'}
        </button>

        <p className="auth__footer">
          Не тот адрес? <Link to="/register">Изменить</Link> · Уже подтвердили?{' '}
          <Link to="/login">Войти</Link>
        </p>
      </form>
    </div>
  );
}
