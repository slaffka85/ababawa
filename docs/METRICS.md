# Метрики и IP клиента

Документ описывает то, что отдаёт `GET /actuator/prometheus` (Spring Boot
Actuator + Micrometer), правила именования и меток, модель доверия к
forwarded-заголовкам и границы наблюдаемости: какие бизнес-события backend
видит достоверно, а какие требуют изменений на клиенте.

Панели Grafana и правила алертов живут в соседнем репозитории **monitoring**:
`grafana/dashboards/ababawa-business.json` (uid `ababawa-business`) и
`prometheus/rules/ababawa.yml` (группа `ababawa-business`).

## Где живут метрики

| Что | Где |
|---|---|
| Счётчики и таймеры бизнес-событий | `com.abavava.chat.observability.BusinessMetrics` — единственная точка создания счётчиков |
| Мгновенные значения realtime-слоя | `com.abavava.chat.observability.RealtimeMetrics` (Gauge читает `realtime.Hub` при скрейпе) |
| HTTP/JVM/HikariCP | Автоконфигурация Spring Boot Actuator |
| Экспозиция | `application.yml` → `management.*`; наружу не публикуется (порт API только на loopback) |
| Опрос | Prometheus стека monitoring, джоба `api` (`prometheus/targets/api/ababawa.yml`) |

Общие метки всех рядов: `application="ababawa"` (из `management.metrics.tags`) и
`job="api"`, `project="ababawa"`, `component="api"`, `env="production"` (из цели
Prometheus). Прикладные метрики начинаются с префикса `ababawa_`.

Гистограммы включены в `application.yml`:
`management.metrics.distribution.percentiles-histogram.http.server.requests` и
`ababawa.calls.duration` — без них `histogram_quantile()` на панелях p95 считать
не из чего.

## Правила меток и кардинальности

1. В метках **нет** IP-адресов, email, `userId`, `callId`, `sessionId`, URL и
   текстов исключений. Это проверяется тестом
   `BusinessMetricsTest.everyMeterUsesOnlyBoundedTagKeysAndSafeValues`.
2. Каждая метка — из закрытого набора значений, заданного в `BusinessMetrics`.
   Значение вне набора превращается в `other`: опечатка или новое значение не
   создаёт новую временную серию.
3. Поэтому число серий каждой метрики предсказуемо: например, у
   `ababawa_auth_otp_requests_total` это 4 (`purpose`) × 2 (`result`) ×
   9 (`reason`) при максимуме, а на практике — единицы.
4. Счётчик появляется в экспозиции при первом событии: метрика без событий даёт
   «no data» в Grafana, а не 0. Гаужи `ababawa_ws_*` есть всегда.
5. `result` — `success|failure`, `reason` — причина отказа (`none` у успеха).

## Каталог метрик

### Регистрация и OTP

| Метрика | Тип | Метки | Смысл |
|---|---|---|---|
| `ababawa_auth_registration_total` | counter | `result`, `reason` | Попытки регистрации. `reason`: `none`, `invalid_input`, `email_taken`, `internal_error` |
| `ababawa_auth_otp_requests_total` | counter | `purpose`, `result`, `reason` | Запросы кода: `registration`, `resend`, `manual`, `password_reset`. Отказы: `rate_limited` (пауза/часовой лимит), `unknown_email`, `not_registered`, `already_verified`, `disabled` (ручка выключена), `mail_not_queued`, `invalid_input`, `internal_error` |
| `ababawa_auth_otp_emails_total` | counter | `kind`, `result` | Письмо с кодом поставлено в очередь (`success`) или нет (`failure`). `kind`: `verification`, `password_reset` |
| `ababawa_auth_otp_verifications_total` | counter | `purpose`, `result`, `reason` | Проверки кода: `email_verify`, `password_reset`. Отказы: `invalid_code`, `expired`, `too_many_attempts`, `already_verified`, `invalid_input`, `internal_error` |
| `ababawa_mail_deliveries_total` | counter | `result` | Фоновая отправка письма по SMTP: принял (`success`) или нет (`failure`) |

### Авторизация

| Метрика | Тип | Метки | Смысл |
|---|---|---|---|
| `ababawa_auth_logins_total` | counter | `result`, `reason` | Вход по email/паролю. Отказы: `invalid_credentials`, `email_not_verified`, `invalid_input`, `internal_error` |
| `ababawa_auth_refresh_total` | counter | `result`, `reason` | Ротация refresh-токена. `token_reuse` — предъявлен отозванный токен (кража сессии), все сессии отозваны |

### Звонки и signaling

| Метрика | Тип | Метки | Смысл |
|---|---|---|---|
| `ababawa_calls_start_total` | counter | `result`, `reason` | Попытки создать звонок. Отказы: `busy`, `callee_not_found`, `self_call`, `invalid_input`, `internal_error` |
| `ababawa_calls_initiated_total` | counter | `kind` | Созданные звонки (`audio`/`video`) |
| `ababawa_calls_accepted_total` | counter | `kind` | Звонок принят вызываемым (signaling-уровень) |
| `ababawa_calls_ended_total` | counter | `status` | Итог звонка: `ended`, `rejected`, `canceled`, `missed`, `failed` |
| `ababawa_calls_signaling_total` | counter | `event`, `result`, `reason` | Пересылка кадра `offer`/`answer`/`candidate`. Отказы: `invalid_payload`, `not_participant`, `call_finished`, `peer_offline`, `not_found`, `internal_error` |
| `ababawa_calls_media_connected_total` | counter | `role` | Клиент сообщил о переходе RTCPeerConnection в `connected` (см. «Границы наблюдаемости») |
| `ababawa_calls_duration_seconds` | timer | `kind` | Длительность завершённого звонка (`answered_at` → `ended_at`), гистограмма |
| `ababawa_call_telemetry_events_total` | counter | `event`, `result` | Клиентская телеметрия NDJSON: `accepted`, `dropped`, `duplicate`; `event` — 17 имён схемы v1, остальные → `other` |

### Подозрительная активность и доступ

| Метрика | Тип | Метки | Смысл |
|---|---|---|---|
| `ababawa_security_rate_limited_total` | counter | `scope` | Запрос отклонён скользящим окном `AuthRateLimitFilter`: `auth` (`/api/v1/auth/*`) или `mail` (`/api/v1/mail/*`) |
| `ababawa_security_access_denied_total` | counter | `channel`, `reason` | Отказ в доступе: `http` (AccessTokenFilter) или `websocket` (WsAuthFilter). `reason`: `missing_token`, `invalid_token`, `user_not_found`, `origin_denied` |

### Realtime

| Метрика | Тип | Метки | Смысл |
|---|---|---|---|
| `ababawa_ws_connections` | gauge | — | Активные WebSocket-соединения (мультидевайс считается поштучно) |
| `ababawa_ws_online_users` | gauge | — | Пользователи хотя бы с одним активным соединением |

## PromQL для панелей

Примеры соответствуют `ababawa-business.json`; `$project` — переменная
дашборда, `$q` ниже означает `job="api",project=~"$project"`.

```promql
# Регистрации за период
sum(increase(ababawa_auth_registration_total{job="api",project=~"$project",result="success"}[$__range])) or vector(0)

# Доля успешных регистраций, %
100 * sum(increase(ababawa_auth_registration_total{job="api",project=~"$project",result="success"}[$__range]))
    / clamp_min(sum(increase(ababawa_auth_registration_total{job="api",project=~"$project"}[$__range])), 1e-9)

# OTP: отправки и ошибки
sum(increase(ababawa_auth_otp_emails_total{job="api",project=~"$project",result="success"}[$__range])) or vector(0)
sum(increase(ababawa_auth_otp_emails_total{job="api",project=~"$project",result="failure"}[$__range])) or vector(0)

# OTP в минуту по назначению (за интервал панели)
sum by (purpose) (increase(ababawa_auth_otp_requests_total{job="api",project=~"$project"}[$__interval]))

# Успешные и неуспешные авторизации
sum by (result) (increase(ababawa_auth_logins_total{job="api",project=~"$project"}[$__interval]))

# Доля неуспешных авторизаций, %
100 * sum(rate(ababawa_auth_logins_total{job="api",project=~"$project",result="failure"}[5m]))
    / clamp_min(sum(rate(ababawa_auth_logins_total{job="api",project=~"$project"}[5m])), 1e-9)

# Звонки: попытки, созданные, принятые
sum by (result, reason) (increase(ababawa_calls_start_total{job="api",project=~"$project"}[$__interval]))
sum by (kind) (increase(ababawa_calls_initiated_total{job="api",project=~"$project"}[$__interval]))
sum by (kind) (increase(ababawa_calls_accepted_total{job="api",project=~"$project"}[$__interval]))

# Процент успешного установления звонков (signaling)
100 * sum(increase(ababawa_calls_accepted_total{job="api",project=~"$project"}[$__range]))
    / clamp_min(sum(increase(ababawa_calls_initiated_total{job="api",project=~"$project"}[$__range])), 1e-9)

# Итоги звонков
sum by (status) (increase(ababawa_calls_ended_total{job="api",project=~"$project"}[$__interval]))

# p95 длительности звонка
histogram_quantile(0.95, sum by (le) (rate(ababawa_calls_duration_seconds_bucket{job="api",project=~"$project"}[$__rate_interval])))

# Частота ошибок signaling
sum by (event) (rate(ababawa_calls_signaling_total{job="api",project=~"$project",result="failure"}[$__rate_interval]))

# OTP rate limit events
sum by (purpose) (increase(ababawa_auth_otp_requests_total{job="api",project=~"$project",reason="rate_limited"}[$__interval]))

# Неуспешные проверки OTP по причине
sum by (purpose, reason) (increase(ababawa_auth_otp_verifications_total{job="api",project=~"$project",result="failure"}[$__interval]))

# Отказы доступа (перебор токенов, Origin)
sum by (channel, reason) (increase(ababawa_security_access_denied_total{job="api",project=~"$project"}[$__interval]))

# Всплеск запросов (общий RPS проекта)
sum(rate(http_server_requests_seconds_count{job="api",project=~"$project",uri!~"/actuator/.*"}[$__rate_interval]))
```

## IP клиента за reverse proxy

Определение адреса — `com.abavava.chat.http.ClientIpResolver`. Единственная
реализация, которую используют rate limit, бизнес-логика (`AuthController`) и
запись IP в `refresh_tokens`/`email_verifications`/`password_resets`.

### Цепочка proxy

```
клиент → Nginx на хосте (TLS, :443) → 127.0.0.1:18082 → контейнер api:8082
```

Nginx собирает `X-Forwarded-For` через `$proxy_add_x_forwarded_for`, то есть
**дописывает** реальный адрес справа к тому, что прислал клиент, и передаёт
`X-Real-IP: $remote_addr`. До этой правки backend брал первый (левый) адрес
`X-Forwarded-For` — любой клиент подставлял себе произвольный IP, обходя
rate limit и защиту от перебора OTP.

### Правила разрешения

1. Если `request.getRemoteAddr()` (непосредственный пир) не входит в доверенные
   сети `abv.trusted-proxies`, forwarded-заголовки **игнорируются целиком**:
   возвращается адрес пира. Подменить IP прямым запросом нельзя.
2. Если пир доверенный, цепочка `X-Forwarded-For` читается **справа налево**:
   первый недоверенный хоп — адрес клиента, который видит ближайший прокси.
   Всё левее мог дописать клиент — отбрасывается.
3. `X-Real-IP` используется только когда в `X-Forwarded-For` нет ни одного
   корректного адреса; `Forwarded` (RFC 7239, `for=`) — как последний запасной
   вариант. Оба тоже требуют доверенного пира.
4. Учитываются только каноничные IP-литералы (IPv4 из четырёх октетов без
   ведущих нулей, IPv6 по `InetAddress.ofLiteral`), DNS при разборе не
   выполняется. `unknown`, зоны (`%eth0`), имена хостов и мусор отбрасываются.
5. IPv4 и IPv6 поддерживаются одинаково; `::ffff:203.0.113.7` приводится к IPv4,
   чтобы доверенные сети совпадали. Результат — каноничный текст адреса.
6. Доверенные сети проверяются при сборке контекста: опечатка в
   `ABV_TRUSTED_PROXIES` роняет старт, а не приводит к тихой подмене IP.

### Конфигурация

```yaml
abv:
  # Значение по умолчанию: loopback + приватные сети Docker. Штатное соединение
  # приходит из bridge-сети (host-Nginx публикует API на loopback) либо с loopback.
  trusted-proxies: "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
```

Переопределение: `ABV_TRUSTED_PROXIES`. Пустое значение — не доверять никому
(все forwarded-заголовки игнорируются). Если API доступен кому-то ещё, кроме
reverse proxy, список надо сузить до адреса прокси (`172.18.0.1/32` для
docker-сети или `127.0.0.1/32`, если публикация идёт через userland-proxy).

Проверки: `ClientIpResolverTest` (подмена слева, прямое соединение,
`X-Real-IP`, `Forwarded`, IPv4/IPv6, IPv4-mapped, битые значения, ошибки
конфигурации) и `HttpxOriginTest` для правил Origin.

## Границы наблюдаемости

Backend **не видит медиатрафик**: WebRTC идёт напрямую между клиентами (через
STUN/TURN). Поэтому:

* `ababawa_calls_accepted_total` — signaling-уровень (callee нажал «принять»).
  Это **не** доказательство установленного соединения.
* `ababawa_calls_media_connected_total` — единственный достоверный признак
  медиасоединения, но он **клиентский**: backend получает событие
  `ice_state_changed` в NDJSON-телеметрии (`details.connection_state =
  "connected"`). Клиент может его не прислать (выключенная телеметрия, закрытая
  вкладка) или прислать повторно после ICE restart, поэтому метрика считает
  переходы, а не звонки, и может превышать 100% от числа звонков.
* Ошибки ICE-восстановления (`ice_restart_exhausted`, `stats_degraded`) есть
  только в клиентской телеметрии — в Grafana это панели Loki-дашборда
  «Calls & Telemetry», а не Prometheus-счётчики. Если нужен именно счётчик,
  минимальное изменение — отправлять с клиента отдельное событие
  `media_established` (один раз на звонок, после `connectionState ===
  "connected"`); backend уже принимает произвольные события схемы v1, нужно
  добавить имя в `CallTelemetryService.EVENT_NAMES` и инкремент счётчика.
* **IP в метках Prometheus запрещён**, поэтому «запросы от одного IP» и
  «всплеск по адресу» не строятся в Prometheus. Источник для этого — логи
  (Loki/Alloy) и существующий rate limit по IP: `ababawa_security_rate_limited_total`
  показывает, что лимит сработал, а конкретный адрес в метку не попадает.
* Доставку письма подтверждает только SMTP-сервер: `ababawa_auth_otp_emails_total`
  фиксирует постановку в очередь, `ababawa_mail_deliveries_total` — ответ SMTP.
  Открытие письма и ввод кода видны косвенно (успешная проверка кода).

## Что уже защищено, а что нет

| Сценарий | Защита сейчас | Наблюдаемость |
|---|---|---|
| Частые запросы OTP | Пауза `resend-cooldown`, лимит `max-resends` за `resend-window`, rate limit 30/мин на IP | `ababawa_auth_otp_requests_total{reason="rate_limited"}`, `ababawa_security_rate_limited_total` |
| Повторные ошибки проверки OTP | `max-attempts` на код, после — код гасится | `ababawa_auth_otp_verifications_total{reason="invalid_code"/"too_many_attempts"}` |
| Частые попытки регистрации | rate limit по IP, пауза на отправку кода | `ababawa_auth_registration_total`, `ababawa_security_rate_limited_total` |
| Массовые неуспешные входы | rate limit по IP | `ababawa_auth_logins_total{result="failure"}` + алерт `AbabawaLoginFailuresHigh` |
| Превышение частоты signaling | **нет лимита** (только проверка участия и активного звонка) | `ababawa_calls_signaling_total` + алерт `AbabawaSignalingErrorsHigh` |
| Аномальное число запросов с одного IP | rate limit только на `/auth/*` и `/mail/*` | по IP — только логи Loki |
| Ошибки доступа к защищённым endpoint | JWT-проверка, Origin для WS | `ababawa_security_access_denied_total` + алерт `AbabawaAccessDeniedSpike` |
| Всплески запросов | rate limit на auth-ручках, остальное — нет | `http_server_requests_seconds_count`, `ababawa_security_rate_limited_total` |

Автоматической блокировки по новым метрикам **нет** и не добавлено: метрики
только наблюдаемость. Отдельного лимита на signaling нет — это кандидат на
следующий этап (token bucket на соединение в `WsClient` или на звонок в
`CallService.signal`, с меткой `reason="rate_limited"`).

## Проверка

```bash
# Офлайн: компиляция и все тесты (включая метрики и client IP)
powershell -ExecutionPolicy Bypass -File build.ps1 check
```

Тесты, фиксирующие контракт с Grafana: `BusinessMetricsTest` (имена в формате
Prometheus exposition, метки, кардинальность, гистограмма длительности),
`ClientIpResolverTest`, `AuthRateLimitFilterTest` (ключ окна — реальный IP, а не
подставленный заголовок), `AuthServiceTest`, `EmailVerificationServiceTest`,
`PasswordResetServiceTest`, `CallServiceMetricsTest`,
`CallTelemetryServiceTest`.
