# Письмо «код подтверждения email»

## Файлы

| Файл | Что это |
|---|---|
| `src/main/resources/mail/verification-code.html` | HTML-часть письма с кодом подтверждения email (`text/html`, multipart/alternative) |
| `src/main/resources/mail/verification-code.txt` | текстовая часть (`text/plain`) — нужна и для доставляемости, и для парсеров |
| `src/main/resources/mail/password-reset.html` | HTML-часть письма со сбросом пароля |
| `src/main/resources/mail/password-reset.txt` | текстовая часть письма со сбросом пароля |
| `docs/mail/verification-code.preview.html` | отрендеренный образец с тестовыми данными (код 483920) — просто откройте в браузере |

Шаблоны лежат в classpath (`classpath:mail/verification-code.html`), потому что
подставляются простой заменой плейсхолдеров — без Thymeleaf/FreeMarker.

## Реализация

| Класс | Что делает |
|---|---|
| `mail.MailTemplates` | читает оба набора файлов из classpath, подставляет `{{...}}`; HTML экранируется, plain-text — нет |
| `mail.VerificationMailer` | считает срок действия и отдаёт письмо (подтверждение email или сброс пароля) отправителю |
| `mail.SmtpMailSender` | очередь на одном фоновом потоке + встроенный SMTP-клиент (`SmtpTransport`), без внешних зависимостей |
| `mail.MimeWriter` | multipart/alternative: base64-части, `Auto-Submitted`, `Message-ID`, защита от инъекции заголовков |

Тема письма собирается кодом — `[Ababawa] email verification code` (у сброса
пароля — `[Ababawa] password reset code`). Кода в теме нет: он не должен
светиться на локскрине и в превью уведомлений, а название действия там и так
есть. Формат повторяет письма GitHub (`[GitHub] Sudo email verification code`).
Тесты: `MailTemplatesTest`, `MimeWriterTest`, `SmtpTransportTest`
(последний поднимает фейковый SMTP-сервер в тесте и проверяет протокол).

## Плейсхолдеры

| Плейсхолдер | Пример | Экранирование |
|---|---|---|
| `{{nickname}}` | `Аня` | в HTML — `&`, `<`, `>`, `"`, `'`; пустое значение → `there` |
| `{{code}}` | `483920` | не нужно (только цифры, проверяется на сервере) |
| `{{expires_minutes}}` | `15` | не нужно |
| `{{support_email}}` | `support@ababawa.tssv85.com` | в HTML — `&`, `"`, `<`, `'` |
| `{{recipient_email}}` | `a***@example.com` | в HTML — то же, обязательно |

## Приветствие

Обе буквы начинаются с `Hey, {{nickname}},` — и в HTML (строкой под заголовком),
и в текстовой части. Никнейм берётся из аккаунта: `EmailVerificationService`
передаёт его при регистрации и повторной отправке, `PasswordResetService` — при
сбросе пароля. У ручной ручки `/api/v1/mail/test` аккаунта нет, поэтому в письме
приветствие `Hey, there,`; пустой никнейм подставляется так же.

## Кодирование частей письма

Обе части (`text/plain` и `text/html`) передаются с
`Content-Transfer-Encoding: base64` (RFC 2045) и `charset=UTF-8`. Это штатный
способ провести UTF-8 по SMTP без 8BITMIME: закодировать, разложить по 76
символов и не зависеть от того, умеет ли релей 8-битные тела. Поэтому в исходнике
письма («Show original» в Gmail, «Показать источник») части выглядят как base64 —
это нормально: клиент декодирует их сам и показывает обычный текст и HTML.
Тема письма и ASCII-заголовки не кодируются, не-ASCII — по RFC 2047 (`=?UTF-8?B?…?=`).
Проверка: `MimeWriterTest` декодирует обе части, а `MailTemplatesTest` сверяет
готовый текст.

## В письме нет ссылок и кнопок

Это осознанное требование: клик по письму не должен никуда вести. Единственное
действие получателя — прочитать код и ввести его в приложении.

- В HTML нет ни одного `<a href>` и вообще ни одного URL — ни в кнопке, ни в
  футере. `MailTemplatesTest.letterHasNoLinksAtAll` это фиксирует.
- Код виден **один раз**, отдельной строкой обычным текстом: он выделяется и
  копируется вручную, а в Gmail его подхватывает нативная пилюля «Copy code».
- В футере есть служебная строка `@<домен> #<код>` для автозаполнения в Apple
  Mail. Это второе вхождение кода, но не ссылка: ни `<a>`, ни `href` у неё нет.
- Собственную кнопку «Copy code» убрали: HTML-письма не исполняют JS, поэтому
  такая кнопка может только переходить по ссылке (а ссылка с кодом ещё и
  светит код в URL, истории и сканерах). Страница `/verify-email` по-прежнему
  умеет принять код из фрагмента `#c=...` — это осталось для писем старого
  формата, новые письма на неё не ведут.

## Subject line

Один формат — `[Ababawa] email verification code` (собирается в
`MailTemplates.verificationCode`). Бренд в скобках + название действия, без кода.

## Письмо сброса пароля

`password-reset.html/.txt` — та же вёрстка и тот же код, другой текст: заголовок
«Reset your password», абзац о запросе сброса и предупреждение игнорировать
письмо, если сброс не запрашивали. Preheader — «Use this one-time code to choose
a new Ababawa password.», служебная строка автозаполнения и футер такие же.
Шаблон наполняется сервисом `PasswordResetService` (см. `docs/API.md`).

## Preheader

```text
Use this one-time verification code to confirm your email address.
```

Это скрытый `<div>` первым в `<body>`. Цифр в нём нет намеренно: до видимого
кода в превью не должно попасть ничего числового, поэтому хвост — «наполнитель»
из `&#847;`, а не `{{expires_minutes}}`.

## Почему Gmail покажет карточку «Copy code», а Apple Mail — автозаполнение

Публичной schema-разметки для карточки кода у Google нет — работает эвристика,
и шаблон под неё подогнан:

- **код — единая plain-text строка** (`<span style="white-space: nowrap;">483920</span>`),
  не разбит по ячейкам и не спрятан в картинку/кнопку/ссылку;
- **код один и тот же** — в видимом блоке и в служебной строке; других
  чисел-кандидатов в письме нет;
- **код стоит высоко** — сразу после «Here is your … verification code:»;
- письмо **без картинок** и без веб-шрифтов (нечему «поплыть» и нечего
  блокировать);
- `meta name="format-detection" content="telephone=no..."` — iOS не превратит
  код в ссылку-телефон, иначе клиент видит «не текст»;
- парсеру помогает и текстовая часть (`verification-code.txt`) — в ней код стоит
  отдельной строкой;
- доставляемость: для карточки Gmail нужен **аутентифицированный отправитель** —
  выровненные `SPF`, `DKIM` и `DMARC`, живой `PTR` (rDNS) на IP VPS. Это
  настраивается на Postfix и в DNS, а не в шаблоне (см. `DEPLOYMENT.md`).

Apple Mail код распознаёт сам (детект живёт 3 минуты после получения), а окно
приложения должно объявить поле как OTP: `autocomplete="one-time-code"`
в вебе/WebView и `textContentType = UITextContentType.oneTimeCode` в нативном
iOS-клиенте.

## Что должен выставить бэкенд при отправке

- `From: Ababawa <no-reply@…>` — домен из настроек, с DKIM-подписью;
- `Reply-To` — не нужен, но в футере указан `{{support_email}}`;
- `Content-Type: multipart/alternative` (text + html);
- `Auto-Submitted: auto-generated` и `X-Auto-Response-Suppress: All` — чтобы на
  письмо не прилетали автоответы и отпускные;
- `List-Unsubscribe` **не** ставим: транзакционное письмо, не рассылка.

## Дизайн

Верстка повторяет подход писем GitHub — они стабильно одинаково понимаются
Gmail и Apple Mail:

- простые вложенные `<table>` с `role="presentation"`, вся геометрия — inline-стили;
- ни `<style>`, ни `@media`, ни `prefers-color-scheme`: тёмную тему клиент
  инвертирует сам, а лишние правила только ломают рендеринг в Gmail;
- ширина контейнера 544 px, отступ 16 px, системный шрифт
  (`-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif`);
- код — 26 px, `font-weight: 600`, `letter-spacing: 4px`, `white-space: nowrap`,
  в рамке `1px #e1e4e8`, радиуса 6 px;
- внешних ресурсов и картинок нет;
- проверка: файл парсится как well-formed XML (XHTML 1.0 Transitional);
  именованных HTML-сущностей нет — только числовые (`&#847;`) и UTF-8-символ `·`.

## Локальный просмотр

```powershell
# заменить плейсхолдеры на тестовые значения и открыть в браузере
$src = "src\main\resources\mail\verification-code.html"
$raw = Get-Content -Raw -Encoding UTF8 $src
$raw = $raw.Replace('{{nickname}}','Anya')
$raw = $raw.Replace('{{code}}','483920').Replace('{{expires_minutes}}','15')
$raw = $raw.Replace('{{support_email}}','support@ababawa.tssv85.com')
$raw = $raw.Replace('{{recipient_email}}','a***@example.com')
Set-Content -Encoding UTF8 "docs\mail\verification-code.preview.html" $raw
start "docs\mail\verification-code.preview.html"
```
