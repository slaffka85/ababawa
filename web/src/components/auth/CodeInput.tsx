import { useEffect, useRef } from 'react';
import type { ClipboardEvent, KeyboardEvent } from 'react';

const DEFAULT_LENGTH = 6;

interface CodeInputProps {
  /** Только цифры, максимум `length` символов. */
  value: string;
  length?: number;
  disabled?: boolean;
  invalid?: boolean;
  autoFocus?: boolean;
  onChange: (value: string) => void;
  /** Вызывается, когда введены все цифры (удобно для авто-подтверждения). */
  onComplete?: (value: string) => void;
}

/**
 * Ячейки под одноразовый код: посимвольный ввод, вставка «483920» целиком
 * (в том числе из буфера обмена), backspace и стрелки между ячейками.
 *
 * <p>`autoComplete="one-time-code"` на первой ячейке включает автозаполнение
 * из письма (iOS/macOS Security Code AutoFill, см. docs/mail/EMAIL-TEMPLATE.md).
 */
export function CodeInput({
  value,
  length = DEFAULT_LENGTH,
  disabled,
  invalid,
  autoFocus,
  onChange,
  onComplete,
}: CodeInputProps) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);
  const digits = value.replace(/\D/g, '').slice(0, length);

  useEffect(() => {
    if (!autoFocus) return;
    refs.current[0]?.focus();
    // фокус нужен один раз при открытии экрана
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const focusAt = (index: number): void => {
    const target = Math.max(0, Math.min(length - 1, index));
    const element = refs.current[target];
    if (!element) return;
    element.focus();
    element.select();
  };

  const apply = (next: string): void => {
    const clean = next.replace(/\D/g, '').slice(0, length);
    onChange(clean);
    focusAt(clean.length);
    if (clean.length === length) onComplete?.(clean);
  };

  const handleChange = (index: number, raw: string): void => {
    const typed = raw.replace(/\D/g, '');
    if (!typed) return;
    if (typed.length > 1) {
      // автозаполнение или вставка в одну ячейку
      apply(digits.slice(0, index) + typed);
      return;
    }
    apply(digits.slice(0, index) + typed + digits.slice(index + 1));
  };

  const handleKeyDown = (index: number, event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Backspace') {
      event.preventDefault();
      if (digits[index]) {
        apply(digits.slice(0, index) + digits.slice(index + 1));
        focusAt(index);
        return;
      }
      if (index === 0) return;
      apply(digits.slice(0, index - 1) + digits.slice(index));
      focusAt(index - 1);
      return;
    }
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      focusAt(index - 1);
      return;
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      focusAt(index + 1);
      return;
    }
    if (event.key === 'Delete') {
      event.preventDefault();
      apply(digits.slice(0, index) + digits.slice(index + 1));
      focusAt(index);
    }
  };

  const handlePaste = (index: number, event: ClipboardEvent<HTMLInputElement>): void => {
    const pasted = event.clipboardData.getData('text').replace(/\D/g, '');
    if (!pasted) return;
    event.preventDefault();
    apply(digits.slice(0, index) + pasted.slice(0, length - index));
  };

  return (
    <div className="code-entry" role="group" aria-label="Код подтверждения">
      {Array.from({ length }, (_, index) => (
        <input
          key={index}
          ref={(element) => {
            refs.current[index] = element;
          }}
          className={`code-entry__cell${invalid ? ' code-entry__cell--invalid' : ''}`}
          type="text"
          value={digits[index] ?? ''}
          inputMode="numeric"
          autoComplete={index === 0 ? 'one-time-code' : 'off'}
          pattern="[0-9]*"
          maxLength={1}
          disabled={disabled}
          aria-label={`Цифра ${index + 1}`}
          onChange={(event) => handleChange(index, event.target.value)}
          onKeyDown={(event) => handleKeyDown(index, event)}
          onPaste={(event) => handlePaste(index, event)}
          onFocus={(event) => event.target.select()}
        />
      ))}
    </div>
  );
}
