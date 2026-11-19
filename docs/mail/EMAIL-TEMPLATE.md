# Письмо «код подтверждения email»

## Файлы

| Файл | Что это |
|---|---|
| `src/main/resources/mail/verification-code.html` | HTML-часть письма (`text/html`, multipart/alternative) |
| `src/main/resources/mail/verification-code.txt` | текстовая часть (`text/plain`) — нужна и для доставляемости, и для парсеров |
| `docs/mail/verification-code.preview.html` | отрендеренный образец с тестовыми данными (код 483920) — просто откройте в браузере |

Шаблоны лежат в classpath (`classpath:mail/verification-code.html`), потому что
подставляются простой заменой плейсхолдеров — без Thymeleaf/FreeMarker.

## Реализация

| Класс | Что делает |
|---|---|
| `mail.MailTemplates` | читает оба файла из classpath, подставляет `{{...}}`; HTML экранируется, plain-text — нет |
| `mail.VerificationMailer` | собирает ссылку `#c=<code>`, срок действия, год и отдаёт письмо отправителю |
| `mail.SmtpMailSender` | очередь на одном фоновом потоке + встроенный SMTP-клиент (`SmtpTransport`), без внешних зависимостей |
| `mail.MimeWriter` | multipart/alternative: base64-части, `Auto-Submitted`, `Message-ID`, защита от инъекции заголовков |

Тема письма собирается кодом — `abavava verification code: <code>` (вариант 1
ниже); чтобы сменить, правьте `MailTemplates.verificationCode`.
Тесты: `MailTemplatesTest`, `MimeWriterTest`, `SmtpTransportTest`
(последний поднимает фейковый SMTP-сервер в тесте и проверяет протокол).

## Плейсхолдеры

| Плейсхолдер | Пример | Экранирование |
|---|---|---|
| `{{code}}` | `483920` | не нужно (только цифры, проверяется на сервере) |
| `{{expires_minutes}}` | `15` | не нужно |
| `{{copy_url}}` | `https://abavava.tssv85.com/verify-email#c=483920` | в HTML-атрибуте — только `&`, `"`, `<` |
| `{{app_url}}` | `https://abavava.tssv85.com` | то же |
| `{{support_email}}` | `support@abavava.tssv85.com` | то же |
| `{{recipient_email}}` | `a***@example.com` | HTML-escape обязательно |
| `{{year}}` | `2026` | не нужно |

Код в `{{copy_url}}` уходит **во фрагменте** (`#c=...`): фрагмент не передаётся
на сервер, поэтому код не попадает в access-логи nginx и в Referer. Страница
`/verify-email` читает его через `location.hash`, кладёт в буфер обмена
(`navigator.clipboard.writeText`) и подставляет в поле ввода. Код остаётся
одноразовым и живёт 15 минут.

## Subject line — 3 варианта

1. **`abavava verification code: 483920`** — рекомендуемый: бренд + триггер-фраза
   `verification code` + код в теме. Такой формат лучше всего совпадает с тем,
   что ищут парсеры Gmail/Apple Mail, и код видно прямо в списке писем.
2. `Your abavava code is 483920` — короче, меньше символов обрезается на мобильных.
3. `483920 is your abavava verification code` — код в начале строки; стиль Google,
   хорошо для превью в списке, но бренд уезжает вправо.

Тему собирает бэкенд: `abavava verification code: %s` (формат держим в одном месте,
чтобы не расходился с шаблоном).

## Preheader

```text
Your code expires in {{expires_minutes}} minutes. Enter it in abavava to confirm your email address.
```

Это скрытый `<div>` первый в `<body>` (после него — «наполнитель» из
`&#8199;&#65279;`, чтобы клиент не подтянул в превью текст из тела письма).

## Почему Gmail покажет карточку «Copy code», а Apple Mail — автозаполнение

Публичной schema-разметки для карточки кода у Google нет — работает эвристика,
и шаблон под неё подогнан:

- **код — единая plain-text строка** (`<span>483920</span>`), не разбит по
  ячейкам и не спрятан в картинку/кнопку/ссылку;
- **код стоит высоко** — третьим блоком, до любых длинных абзацев;
- **рядом триггер-фраза** `Your verification code is:`;
- письмо **без картинок** и без веб-шрифтов (нечему «поплыть» и нечего блокировать);
- `meta name="format-detection" content="telephone=no..."` — iOS не превратит код
  в ссылку-телефон, иначе клиент видит «не текст»;
- парсеру помогает и текстовая часть (`verification-code.txt`) — там код стоит
  отдельной строкой;
- доставляемость: для карточки Gmail нужен **аутентифицированный отправитель** —
  выровненные `SPF`, `DKIM` и `DMARC`, живой `PTR` (rDNS) на IP VPS. Это
  настраивается на Postfix, а не в шаблоне.

Apple Mail код распознаёт сам (детект живёт 3 минуты после получения), а окно
приложения должно объявить поле как OTP: `autocomplete="one-time-code"`
в вебе/WebView и `textContentType = UITextContentType.oneTimeCode` в нативном
iOS-клиенте.

## Что должен выставить бэкенд при отправке

- `From: abavava <no-reply@abavava.tssv85.com>` — домен из настроек, с DKIM-подписью;
- `Reply-To` — не нужен, но в футере указан `{{support_email}}`;
- `Content-Type: multipart/alternative` (text + html);
- `Auto-Submitted: auto-generated` и `X-Auto-Response-Suppress: All` — чтобы на
  письмо не прилетали автоответы и отпускные;
- `List-Unsubscribe` **не** ставим: транзакционное письмо, не рассылка.

## Дизайн

- табличная вёрстка, все стили inline, `<script>` отсутствует, внешних ресурсов нет;
- ширина 600 px (`max-width` + MSO-условие на `width="600"`);
- адаптив: `@media (max-width: 480px)` — уменьшенные отступы, код 28 px;
- тёмная тема: `color-scheme: light dark` + `@media (prefers-color-scheme: dark)`
  + селекторы `[data-ogsc]` для Outlook.com; базовые цвета взяты средними по
  яркости, чтобы принудительная инверсия Gmail их не «сломала»;
- проверка: файл парсится как well-formed XML (XHTML 1.0 Transitional),
  именованные сущности (`&nbsp;`, `&copy;`, `&middot;`) заменены на числовые.

## Локальный просмотр

```powershell
# заменить плейсхолдеры на тестовые значения и открыть в браузере
$src = "src\main\resources\mail\verification-code.html"
$raw = Get-Content -Raw -Encoding UTF8 $src
$raw = $raw.Replace('{{code}}','483920').Replace('{{expires_minutes}}','15')
$raw = $raw.Replace('{{copy_url}}','https://abavava.tssv85.com/verify-email#c=483920')
$raw = $raw.Replace('{{app_url}}','https://abavava.tssv85.com')
$raw = $raw.Replace('{{support_email}}','support@abavava.tssv85.com')
$raw = $raw.Replace('{{recipient_email}}','a***@example.com').Replace('{{year}}','2026')
Set-Content -Encoding UTF8 "docs\mail\verification-code.preview.html" $raw
start "docs\mail\verification-code.preview.html"
```
