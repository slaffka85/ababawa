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
| `mail.VerificationMailer` | считает срок действия и отдаёт письмо отправителю |
| `mail.SmtpMailSender` | очередь на одном фоновом потоке + встроенный SMTP-клиент (`SmtpTransport`), без внешних зависимостей |
| `mail.MimeWriter` | multipart/alternative: base64-части, `Auto-Submitted`, `Message-ID`, защита от инъекции заголовков |

Тема письма собирается кодом — `[Ababawa] email verification code`. Кода в теме
нет: он не должен светиться на локскрине и в превью уведомлений, а название
действия там и так есть. Формат повторяет письма GitHub
(`[GitHub] Sudo email verification code`).
Тесты: `MailTemplatesTest`, `MimeWriterTest`, `SmtpTransportTest`
(последний поднимает фейковый SMTP-сервер в тесте и проверяет протокол).

## Плейсхолдеры

| Плейсхолдер | Пример | Экранирование |
|---|---|---|
| `{{code}}` | `483920` | не нужно (только цифры, проверяется на сервере) |
| `{{expires_minutes}}` | `15` | не нужно |
| `{{support_email}}` | `support@abavava.tssv85.com` | в HTML — `&`, `"`, `<`, `'` |
| `{{recipient_email}}` | `a***@example.com` | в HTML — то же, обязательно |

## В письме нет ссылок и кнопок

Это осознанное требование: клик по письму не должен никуда вести. Единственное
действие получателя — прочитать код и ввести его в приложении.

- В HTML нет ни одного `<a href>` и вообще ни одного URL — ни в кнопке, ни в
  футере. `MailTemplatesTest.letterHasNoLinksAtAll` это фиксирует.
- Код в письме встречается **ровно один раз**, отдельной строкой обычным
  текстом: он выделяется и копируется вручную, а в Gmail его подхватывает
  нативная пилюля «Copy code».
- Собственную кнопку «Copy code» убрали: HTML-письма не исполняют JS, поэтому
  такая кнопка может только переходить по ссылке (а ссылка с кодом ещё и
  светит код в URL, истории и сканерах). Страница `/verify-email` по-прежнему
  умеет принять код из фрагмента `#c=...` — это осталось для писем старого
  формата, новые письма на неё не ведут.

## Subject line

Один формат — `[Ababawa] email verification code` (собирается в
`MailTemplates.verificationCode`). Бренд в скобках + название действия, без кода.

## Preheader

```text
Your code is valid for {{expires_minutes}} minutes.
```

Это скрытый `<div>` первым в `<body>` (после него — «наполнитель» из
`&#8199;&#65279;`, чтобы клиент не подтянул в превью текст из тела письма).

## Почему Gmail покажет карточку «Copy code», а Apple Mail — автозаполнение

Публичной schema-разметки для карточки кода у Google нет — работает эвристика,
и шаблон под неё подогнан:

- **код — единая plain-text строка** (`<td>483920</td>`), не разбит по ячейкам
  и не спрятан в картинку/кнопку/ссылку;
- **в письме один код** и нет других чисел, которые можно спутать с ним;
- **код стоит высоко** — сразу после «Here is your … verification code:»;
- письмо **без картинок** и без веб-шрифтов (нечему «поплыть» и нечего
  блокировать);
- `meta name="format-detection" content="telephone=no..."` — iOS не превратит
  код в ссылку-телефон, иначе клиент видит «не текст»;
- парсеру помогает и текстовая часть (`verification-code.txt`) — в ней код тоже
  присутствует ровно один раз и обычным текстом;
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
- код — 24 px с `letter-spacing: 4px`, в рамке `1px #e1e4e8`, радиуса 6 px;
- внешних ресурсов и картинок нет;
- проверка: файл парсится как well-formed XML (XHTML 1.0 Transitional),
  именованные сущности (`&middot;`) заменены числовыми (`&#183;`).

## Локальный просмотр

```powershell
# заменить плейсхолдеры на тестовые значения и открыть в браузере
$src = "src\main\resources\mail\verification-code.html"
$raw = Get-Content -Raw -Encoding UTF8 $src
$raw = $raw.Replace('{{code}}','483920').Replace('{{expires_minutes}}','15')
$raw = $raw.Replace('{{support_email}}','support@abavava.tssv85.com')
$raw = $raw.Replace('{{recipient_email}}','a***@example.com')
Set-Content -Encoding UTF8 "docs\mail\verification-code.preview.html" $raw
start "docs\mail\verification-code.preview.html"
```
