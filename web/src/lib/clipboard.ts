/**
 * Буфер обмена. Код подтверждения удобнее вставить, чем набирать, но доступ к
 * clipboard API есть не всегда: в WebView и на http-origin он может быть
 * запрещён — тогда отдаём false/null, а интерфейс предлагает ввод руками.
 */

export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // нет разрешения — пробуем старый способ ниже
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '0';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand('copy');
    document.body.removeChild(area);
    return copied;
  } catch {
    return false;
  }
}

/** Текст из буфера обмена или null, если браузер не дал доступ. */
export async function readClipboard(): Promise<string | null> {
  try {
    if (navigator.clipboard && navigator.clipboard.readText) {
      return await navigator.clipboard.readText();
    }
  } catch {
    // разрешение не выдано
  }
  return null;
}
