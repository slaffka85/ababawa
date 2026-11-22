# abavava — REST API (v1)

Базовый URL: `http://localhost:8080/api/v1`
Формат: JSON, `snake_case`. Даты — RFC3339 UTC (`2025-01-31T12:00:00Z`).
Все защищённые эндпоинты требуют заголовок `Authorization: Bearer <access_token>`.

## Формат ошибок

```json
{ "error": { "code": "invalid_request", "message": "email is required", "details": { "field": "email" } } }
```

| code | HTTP | Когда |
|---|---|---|
| `invalid_request` | 400 | битый JSON, отсутствуют поля |
| `validation_failed` | 422 | поля есть, но не проходят валидацию |
| `unauthorized` | 401 | нет/просрочен/битый access-токен, неверный пароль |
| `forbidden` | 403 | нет доступа к ресурсу |
| `not_found` | 404 | ресурс не существует |
| `conflict` | 409 | email занят, звонок уже завершён |
| `rate_limited` | 429 | превышен лимит |
| `internal` | 500 | внутренняя ошибка |

## Модели

```jsonc
// User
{ "id": "uuid", "email": "a@b.c", "nickname": "Аня", "avatar_url": null,
  "created_at": "2025-01-31T12:00:00Z", "last_seen_at": "2025-01-31T12:05:00Z" }

// Message
{ "id": "uuid", "conversation_id": "uuid", "sender_id": "uuid",
  "kind": "text",                    // text | system | call
  "body": "привет", "client_id": "c-1",
  "created_at": "2025-01-31T12:00:00Z", "edited_at": null, "deleted_at": null }

// ConversationView
{ "conversation": { "id": "uuid", "kind": "direct",  // direct | group
                    "title": null, "created_by": "uuid",
                    "created_at": "...", "updated_at": "...", "last_message_at": "..." },
  "members": [ /* User */ ],
  "last_message": { /* Message */ } | null,
  "unread_count": 3 }

// Call
{ "id": "uuid", "conversation_id": "uuid", "caller_id": "uuid", "callee_id": "uuid",
  "kind": "video",                   // audio | video
  "status": "ringing",               // ringing | accepted | rejected | missed | ended | failed | canceled
  "created_at": "...", "answered_at": null, "ended_at": null,
  "ended_by": null, "end_reason": null,
  "duration_seconds": 0,
  "peer": { /* User — собеседник относительно запросившего */ } }
```

## Health

| Метод | Путь | Описание |
|---|---|---|
| GET | `/healthz` | liveness, `{"status":"ok"}` (также доступен как `/api/v1/healthz`) |
| GET | `/readyz` | readiness + пинг БД, `{"status":"ok","db":"ok"}` |
| GET | `/status` | 🔒 диагностика транспорта: `{"online_users":2,"connections":3,"server_time":"..."}` |

## Auth

Регистрация двухшаговая: аккаунт создаётся неподтверждённым, на почту уходит
6-значный код (живёт 15 минут, писем не больше 5 в час), а пара токенов
появляется только после `/auth/verify`. Письмо собирается шаблоном
`mail/verification-code.html` (см. `docs/mail/EMAIL-TEMPLATE.md`). Сброс пароля
(`/auth/password/forgot` → `/auth/password/reset`) использует ту же схему кодов
и писем с шаблоном `mail/password-reset.html`.

### POST `/auth/register`
```json
{ "email": "a@b.c", "nickname": "Аня", "password": "secret123" }
```
`202` → аккаунт создан, письмо с кодом отправлено (токенов в ответе нет):
```json
{ "verification_id": "uuid",
  "email": "a***@b.c",
  "expires_in": 900,
  "resend_in": 60,
  "email_sent": true,
  "dev_code": null }
```
- `email` — замаскированный адрес; `email_sent` — письмо принято к отправке
  (`false`, если SMTP выключен или очередь переполнена);
- `dev_code` заполняется **только** при `ABV_ENV=development`, в production
  всегда `null`: код существует исключительно в письме.

Ошибки: `422` (email/nickname невалидны), `409` (email занят).

Профильное поле называется `nickname` (до миграции `0005_nickname.sql` —
`display_name`): колонка переименована без потери данных. Для клиентов,
закешированных до переименования, `display_name` ещё принимается на входе
`/auth/register` и `PATCH /me` как алиас, но в ответах всегда только `nickname`.

### POST `/auth/verify`
```json
{ "email": "a@b.c", "code": "483920" }
```
`200` → как у login: `{ "user": User, "access_token": "...", "refresh_token": "...", "expires_in": 900 }`.

Ошибки (все с `details.field = "code"`):
| HTTP | code | details | Когда |
|---|---|---|---|
| `422` | `validation_failed` | `attempts_left` | неверный код; на код даётся 5 попыток |
| `422` | `validation_failed` | `reason: "expired"` | код истёк или не запрашивался |
| `422` | `validation_failed` | `reason: "too_many_attempts"` | попытки исчерпаны, код погашен |
| `409` | `conflict` | — | адрес уже подтверждён |

### POST `/auth/resend`
```json
{ "email": "a@b.c" }
```
`202` → `{ "resend_in": 60, "expires_in": 900, "email_sent": true }`. Предыдущий
код гасится: действующим остаётся только последний. В development в ответ
добавляется `dev_code` с новым кодом.

`429` `rate_limited` — слишком часто (`details: { "field": "email", "resend_in": 59 }`)
или исчерпан лимит писем (`details: { "retry_in": 3600 }`).
Для незарегистрированного адреса ответ такой же `202`, но письмо не уходит.

### POST `/auth/send-code`
```json
{ "email": "a@b.c" }
```
Ручная отправка кода на почту — проверить SMTP и пройти сценарий, не заполняя
форму и не дожидаясь паузы. Работает только с **существующим** аккаунтом
(сначала `/auth/register`). Подтверждённый адрес письмо тоже получает: это
ручной инструмент проверки доставки, но код из такого письма не пройдёт
`/auth/verify` — адрес уже подтверждён.

| HTTP | code | details | Когда |
|---|---|---|---|
| `202` | — | — | письмо отправлено: `{ "resend_in": 0, "expires_in": 900, "email_sent": true, "dev_code": "483920" }` |
| `409` | `conflict` | `reason: "email_not_registered"` | такого адреса в базе нет |
| `404` | `not_found` | — | эндпоинт выключен (`send-code: off` или `dev` в production) |
| `429` | `rate_limited` | `resend_in` / `retry_in` | в режиме `on`: пауза или часовой лимит |

Доступ задаётся `abv.email-verification.send-code`
(`ABV_EMAILVERIFICATION_SENDCODE`):

| значение | когда работает | пауза и лимит |
|---|---|---|
| `off` | никогда (`404`) | — |
| `dev` (по умолчанию) | только при `ABV_ENV != production` | игнорируются |
| `on` | всегда | `resend-cooldown` и `max-resends`, как у `/auth/resend` |

Предыдущий код гасится: действующим остаётся последний отправленный.

### POST `/auth/password/forgot`

```json
{ "email": "a@b.c" }
```

`202` → код отправлен письмом (тема `[Ababawa] password reset code`, та же
вёрстка, что у письма подтверждения):

```json
{ "email": "a***@b.c", "expires_in": 900, "resend_in": 60, "dev_code": null }
```

Ответ одинаков для существующего и неизвестного адреса: неизвестный просто не
получает письмо (защита от перебора базы). Код и лимиты — из
`abv.email-verification` (TTL, 5 попыток, пауза и часовой лимит отправок, как у
`/auth/resend`). `429` `rate_limited` — `details.resend_in` / `details.retry_in`.

### POST `/auth/password/reset`

```json
{ "email": "a@b.c", "code": "483920", "password": "new-secret123" }
```

`204` → пароль сменён, все refresh-токены пользователя отозваны (нужно войти
заново). Пароль — от 8 до 72 байт. Код гасится; предыдущие коды сброса тоже.

| HTTP | code | details | Когда |
|---|---|---|---|
| `422` | `validation_failed` | `field: "password"` | пароль короче 8 или длиннее 72 байт |
| `422` | `validation_failed` | `field: "code"`, `attempts_left` | неверный код |
| `422` | `validation_failed` | `reason: "expired"` | код истёк или не запрашивался |
| `422` | `validation_failed` | `reason: "too_many_attempts"` | попытки исчерпаны, код погашен |

Для незарегистрированного адреса — тот же `422` «invalid password reset code»:
существование аккаунта не раскрывается.

### POST `/auth/login`
```json
{ "email": "a@b.c", "password": "secret123" }
```
`200` → как у verify. `401` при неверных данных (сообщение общее, без раскрытия).
`403` `forbidden` с `details: { "reason": "email_not_verified", "email": "a@b.c" }`,
если пароль верный, но почта не подтверждена — клиент уводит на ввод кода.

«Запомнить меня» — решение клиента, а не сервера: refresh-токен живёт
`refresh-ttl` (30 дней) в любом случае, а клиент выбирает, где его хранить —
постоянно (localStorage) или до закрытия вкладки (sessionStorage). Сервер об
этом не знает.

### POST `/auth/refresh`
```json
{ "refresh_token": "..." }
```
`200` → новая пара токенов (refresh ротируется: старый становится недействительным).
`401`, если токен неизвестен, просрочен или отозван.

### POST `/auth/logout`
```json
{ "refresh_token": "..." }
```
`204`. Отзывает refresh-токен. Идемпотентно.

### GET `/me`
`200` → `{ "user": User }`

### PATCH `/me`
```json
{ "nickname": "Аня П.", "avatar_url": "https://..." }
```
`200` → `{ "user": User }`

## Почта (временный тест)

### POST `/mail/test`

Ручка для настройки SMTP: генерирует код и **синхронно** отправляет письмо
подтверждения на любой адрес, в ответе — результат SMTP-сеанса. Аккаунт не
нужен; код не сохраняется в БД, поэтому `/auth/verify` его не примет (для
настоящего сценария — `/auth/register`).

```json
{ "email": "you@example.com" }
```
`200`:
```json
{ "email": "you@example.com", "code": "483920", "sent": true }
```
`200`, если SMTP недоступен или письмо отклонено ({`error` — исключение
транспорта, `code` сгенерирован, но письмо не ушло):
```json
{ "email": "you@example.com", "code": "483920", "sent": false,
  "error": "java.net.ConnectException: Connection refused" }
```
`422` `validation_failed` — пустой или невалидный `email`. `404` — ручка
выключена. HTTP-код всегда `200`, когда запрос дошёл до отправки: результат
разбирается по `sent`/`error`, а не по статусу.

Ручка существует только при `abv.mail.test-endpoint=true`
(`ABV_MAIL_TEST_ENDPOINT`): по умолчанию и на VPS флаг выключен, контроллер не
создаётся, маршрут отвечает `404`. `ABV_EMAILVERIFICATION_SENDCODE` на неё не
влияет. Включённая ручка ограничена общим rate-limit (30 в минуту на IP).

**Временная ручка:** после настройки почты удалите `MailTestController`,
`/api/v1/mail/` в `AccessTokenFilter` и `/api/v1/mail/*` в `WebFilterConfig`.

## Пользователи

| Метод | Путь | Описание |
|---|---|---|
| GET | `/users?q=<подстрока>&limit=20` | поиск по `nickname`/`email`, исключая себя |
| GET | `/users/{id}` | профиль; `404`, если нет |

## Диалоги

### GET `/conversations?limit=50&cursor=<last_message_at>`
`200` → `{ "items": [ConversationView], "next_cursor": "..." | null }`, отсортировано по `last_message_at desc nulls last`.

### POST `/conversations`
Direct (идемпотентно — повторный запрос возвращает тот же диалог, `200` вместо `201`):
```json
{ "kind": "direct", "peer_id": "uuid" }
```
Группа:
```json
{ "kind": "group", "title": "Команда", "member_ids": ["uuid", "uuid"] }
```
`201` → `ConversationView`. Ошибки: `404` (peer не существует), `422`.

### GET `/conversations/{id}`
`200` → `ConversationView`. `403`, если пользователь не участник.

### POST `/conversations/{id}/members` (только группа, владелец)
```json
{ "user_ids": ["uuid"] }
```
`200` → `ConversationView`

### DELETE `/conversations/{id}/members/{user_id}`
`204`. Владелец может удалить любого, участник — только себя (выход). В UI участник видит «выйти», владелец — «удалить».

### GET `/conversations/{id}/messages?limit=50&before=<message_id>`
Keyset-пагинация назад по времени. `200` → `{ "items": [Message], "has_more": true }`
`items` — от старых к новым внутри страницы.

### POST `/conversations/{id}/messages`
```json
{ "body": "привет", "client_id": "c-1" }
```
`201` → `{ "message": Message }`. Сервер также шлёт `message.new` всем участникам по WS.
`client_id` обеспечивает идемпотентность: повтор с тем же `client_id` возвращает `200` и уже созданное сообщение.

### POST `/conversations/{id}/read`
```json
{ "message_id": "uuid" }
```
`204`. Обновляет `last_read`, рассылает `message.read` по WS.

### POST `/conversations/{id}/typing`
```json
{ "state": "start" }
```
`204`. REST-дубль WS-события `typing` (для клиентов без WS).

## Звонки

Состояния: `ringing → accepted → ended`; из `ringing` возможны `rejected`, `canceled` (звонящий отменил), `missed` (таймаут 45 с).
Терминальные: `ended`, `rejected`, `missed`, `failed`, `canceled`.

### POST `/calls`
```json
{ "callee_id": "uuid", "kind": "video", "conversation_id": "uuid" }
```
`conversation_id` опционален: если не указан, создаётся/находится direct-диалог с `callee_id`.
`201` → `{ "call": Call }`. Калильщик получает `call.created`, вызываемый — `call.incoming` по WS.
`409`, если у участника уже есть активный звонок.

### GET `/calls?limit=50`
История (и входящие, и исходящие), сортировка по `created_at desc`.
`200` → `{ "items": [Call] }`

### GET `/calls/{id}`
`200` → `{ "call": Call }` (только участники).

### POST `/calls/{id}/accept` · `/reject` · `/end`
Тело опционально: `{ "reason": "hangup" }`.
`200` → `{ "call": Call }`. Оба участника получают `call.updated` по WS.
`409`, если переход состояния недопустим (например, `end` уже завершённого звонка).

## WebSocket

Описание протокола и WebRTC-сигналинга: см. `docs/WS-PROTOCOL.md`.
Эндпоинт: `GET /api/v1/ws?token=<access_token>` (тот же origin, что и REST).

## Переменные окружения (backend)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `ABV_HTTP_ADDR` | `:8080` | адрес прослушивания |
| `ABV_DATABASE_URL` | `postgres://abv:abv@localhost:5432/abv?sslmode=disable` | DSN PostgreSQL |
| `ABV_JWT_SECRET` | `dev-insecure-secret-change-me` | ключ подписи HS256 |
| `ABV_ACCESS_TTL` | `15m` | время жизни access-токена |
| `ABV_REFRESH_TTL` | `720h` | время жизни refresh-токена |
| `ABV_CALL_RING_TIMEOUT` | `45s` | до перевода в `missed` |
| `ABV_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174` | CORS и Origin у WebSocket (список через запятую) |
| `ABV_LOG_LEVEL` | `info` | `debug`/`info`/`warn`/`error` |
