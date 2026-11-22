import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { IconSpinner } from '../components/Icons';
import { savePending } from '../lib/pending';
import { readRememberedEmail } from '../lib/api';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const login = useAuthStore((state) => state.login);
  const user = useAuthStore((state) => state.user);
  const error = useAuthStore((state) => state.error);
  const clearError = useAuthStore((state) => state.clearError);

  // Адрес из сброса пароля важнее запомненного: человек только что его вводил.
  const [email, setEmail] = useState(() => {
    const fromReset = (location.state as { email?: string } | null)?.email;
    return fromReset ?? readRememberedEmail();
  });
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (user) navigate('/', { replace: true });
  }, [user, navigate]);

  useEffect(() => () => clearError(), [clearError]);

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    const nextErrors: { email?: string; password?: string } = {};
    if (!email.trim()) nextErrors.email = 'Укажите email';
    else if (!EMAIL_RE.test(email.trim())) nextErrors.email = 'Похоже, email указан неверно';
    if (!password) nextErrors.password = 'Введите пароль';
    setFieldErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    setSubmitting(true);
    const outcome = await login(email, password, remember);
    setSubmitting(false);
    if (outcome === 'ok') {
      navigate('/', { replace: true });
      return;
    }
    if (outcome === 'unverified') {
      // Пароль верный, но адрес не подтверждён: уводим на ввод кода из письма.
      clearError();
      const pending = { email: email.trim().toLowerCase(), resendIn: 0, expiresIn: 0 };
      savePending(pending);
      navigate('/verify-email', { replace: true, state: pending });
    }
  };

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={handleSubmit} noValidate>
        <h1 className="auth__title">chat-ababawa</h1>
        <p className="auth__subtitle">Войдите, чтобы продолжить общение</p>

        {error ? <p className="auth__error">{error}</p> : null}

        <label className="field">
          <span className="field__label">Email</span>
          <input
            className={`field__input${fieldErrors.email ? ' field__input--invalid' : ''}`}
            type="email"
            value={email}
            autoComplete="email"
            placeholder="you@example.com"
            onChange={(event) => {
              setEmail(event.target.value);
              if (error) clearError();
            }}
          />
          {fieldErrors.email ? <span className="field__hint field__hint--error">{fieldErrors.email}</span> : null}
        </label>

        <label className="field">
          <span className="field__label">Пароль</span>
          <input
            className={`field__input${fieldErrors.password ? ' field__input--invalid' : ''}`}
            type="password"
            value={password}
            autoComplete="current-password"
            placeholder="••••••••"
            onChange={(event) => {
              setPassword(event.target.value);
              if (error) clearError();
            }}
          />
          {fieldErrors.password ? (
            <span className="field__hint field__hint--error">{fieldErrors.password}</span>
          ) : null}
        </label>

        <div className="auth__row">
          <label className="auth__check">
            <input
              type="checkbox"
              checked={remember}
              onChange={(event) => setRemember(event.target.checked)}
            />
            <span>Запомнить меня</span>
          </label>
          <Link className="auth__link" to="/forgot-password">
            Забыли пароль?
          </Link>
        </div>

        <button type="submit" className="btn btn--primary btn--block" disabled={submitting}>
          {submitting ? <IconSpinner size={18} /> : null}
          Войти
        </button>

        <p className="auth__footer">
          Нет аккаунта? <Link to="/register">Зарегистрироваться</Link>
        </p>
      </form>
    </div>
  );
}
