# Продакшен-деплой Abavava на VPS

Все части приложения работают в Docker Compose: `web` (React и Nginx со
статическими файлами), `api` (Java) и `postgres`. PostgreSQL опубликован только
на loopback (`127.0.0.1:5432`) — в интернет порт не смотрит, снаружи
подключайтесь через SSH-туннель. Frontend опубликован на `127.0.0.1:18080`,
API — на `127.0.0.1:18082`; существующий Nginx на хосте завершает TLS и
проксирует запросы в эти два контейнера.

## Nginx на хосте

Замените `root`/`try_files` в HTTPS-блоке на proxy locations ниже. Сохраните
существующие `listen`, сертификаты и параметры TLS. Пример целиком для указанного
домена:

```nginx
server {
    listen 127.0.0.1:8443 ssl http2;
    server_name abavava.tssv85.com;

    ssl_certificate /etc/nginx/ssl/abavava.tssv85.com/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/abavava.tssv85.com/privkey.pem;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # REST API и WebSocket идут в Java-контейнер.
    location /api/ {
        proxy_pass http://127.0.0.1:18082;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }

    # Остальные запросы и SPA-маршруты идут в web-контейнер.
    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name abavava.tssv85.com;

    # Оставьте этот location, если Certbot использует webroot /var/www/html.
    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}
```

`proxy_pass` без завершающего `/` сохраняет исходный путь `/api/...`. Это важно
для маршрутов API и WebSocket `/api/v1/ws`. Убедитесь, что внешний TLS-proxy,
если он у вас есть, направляет HTTPS на `127.0.0.1:8443`: этот listener привязан
только к loopback. Если этот Nginx сам принимает публичный HTTPS напрямую,
используйте его фактический `listen` на публичном интерфейсе/порту вместо
`127.0.0.1:8443`.

После запуска контейнеров проверьте и перечитайте конфигурацию:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

## Подготовьте VPS

1. Установите Docker Engine и Compose plugin. Проверьте `docker compose version`.
2. Создайте deploy-пользователя с доступом к Docker и правом записи в
   `/opt/abavava`. Членство в группе `docker` фактически даёт права root;
   ограничьте SSH-ключ и workflow, которым разрешён деплой.
3. Подготовьте каталог от администратора, заменив `deploy` на имя пользователя:

   ```bash
   install -d -o deploy -g deploy -m 0755 /opt/abavava
   ```

4. С локального клона скопируйте пример и заполните его на VPS:

   ```bash
   scp .env.production.example deploy@<VPS_HOST>:/opt/abavava/.env.production
   ssh deploy@<VPS_HOST>
   chmod 600 /opt/abavava/.env.production
   nano /opt/abavava/.env.production
   ```

   Укажите `ABV_DOMAIN=abavava.tssv85.com`. Сгенерируйте уникальные секреты:

   ```bash
   openssl rand -hex 32
   openssl rand -base64 48
   ```

   Первое значение задайте как `ABV_DATABASE_PASSWORD`, второе как
   `ABV_JWT_SECRET`. Храните заполненный файл только на сервере. Пароль БД
   должен оставаться неизменным после создания volume, если вы отдельно не
   меняете пароль в PostgreSQL.

   Для писем с кодом подтверждения адреса по умолчанию выводятся из
   `ABV_DOMAIN` (deploy workflow пишет `no-reply@<домен>` и `support@<домен>` в
   `.deploy.env`). Переопределите их в `.env.production`, если почтовый домен
   отличается:

   ```dotenv
   ABV_MAIL_FROM=no-reply@ababawa.tssv85.com
   ABV_MAIL_SUPPORT_EMAIL=support@ababawa.tssv85.com
   ```

## Почта для подтверждения email

Регистрация отправляет код через SMTP. Транспорт встроен в API (без внешних
зависимостей), поэтому достаточно, чтобы контейнер `api` видел SMTP-сервер.
По умолчанию это Postfix на хосте через `host.docker.internal:25` —
`extra_hosts: host.docker.internal:host-gateway` уже прописан в
`compose.prod.yaml`.

### Postfix на хосте

1. Установите Postfix (Internet Site, system mail name — домен из `ABV_DOMAIN`):

   ```bash
   sudo apt install -y postfix
   sudo systemctl enable --now postfix
   ```

2. Разрешите приём писем из docker-сети, оставив релей закрытым:

   ```bash
   sudo postconf -e 'inet_interfaces = all'
   sudo postconf -e 'mynetworks = 127.0.0.0/8 172.16.0.0/12'
   sudo postconf -e 'smtpd_relay_restrictions = permit_mynetworks, reject_unauth_destination'
   sudo postconf -e 'message_size_limit = 10240000'
   sudo systemctl reload postfix
   sudo ss -ltnp | grep ':25'
   ```

   `mynetworks` доверяет всей docker-сети: контейнер может отправить письмо от
   любого отправителя, поэтому порт 25 не должен быть доступен из интернета.
   Если нужен только docker-трафик:

   ```bash
   sudo ufw deny 25/tcp
   sudo ufw allow from 172.16.0.0/12 to any port 25 proto tcp
   ```

3. Проверьте отправку с хоста:

   ```bash
   printf 'Subject: postfix test\n\nhello\n' | sudo sendmail -f no-reply@ababawa.tssv85.com you@example.com
   sudo tail -n 30 /var/log/mail.log
   ```

### DNS: SPF, DKIM, DMARC, rDNS

Без аутентификации отправителя письма с кодом уходят в спам, а Gmail не
показывает нативную карточку «Copy code». Нужны все три записи плюс PTR.

1. SPF — разрешите IP сервера:

   ```
   ababawa.tssv85.com.  TXT  "v=spf1 a:ababawa.tssv85.com ip4:<VPS_IP> -all"
   ```

2. DKIM — сгенерируйте ключ и подключите OpenDKIM:

   ```bash
   sudo apt install -y opendkim opendkim-tools
   sudo mkdir -p /etc/opendkim/keys/ababawa.tssv85.com
   cd /etc/opendkim/keys/ababawa.tssv85.com
   sudo opendkim-genkey -s mail -d ababawa.tssv85.com
   sudo chown opendkim:opendkim mail.private
   sudo cat mail.txt          # готовая TXT-запись для DNS
   ```

   ```
   mail._domainkey.ababawa.tssv85.com.  TXT  "v=DKIM1; k=rsa; p=<публичный ключ>"
   ```

   В `/etc/opendkim.conf`: `Domain ababawa.tssv85.com`, `Selector mail`, затем
   `sudo systemctl enable --now opendkim` и в Postfix:

   ```bash
   sudo postconf -e 'milter_default_action = accept'
   sudo postconf -e 'smtpd_milters = inet:localhost:12301'
   sudo postconf -e 'non_smtpd_milters = inet:localhost:12301'
   sudo systemctl reload postfix
   ```

3. DMARC:

   ```
   _dmarc.ababawa.tssv85.com.  TXT  "v=DMARC1; p=quarantine; rua=mailto:postmaster@ababawa.tssv85.com; adkim=s; aspf=s"
   ```

4. PTR (rDNS) — в панели провайдера VPS: `<VPS_IP> → ababawa.tssv85.com`.
   Без PTR Gmail и Microsoft режут письма. Имя из PTR должно совпадать с
   баннером SMTP (`220 <имя> ESMTP Postfix`): его задаёт `myhostname` в
   Postfix, а проверяющие сверяют PTR и A в обе стороны (FCrDNS).

5. Поддомен **не наследует** записи родителя. SPF и DKIM ищутся ровно по
   домену из `From` — если письмо уходит с `no-reply@ababawa.tssv85.com`, то
   записи, сделанные для `tssv85.com`, его не аутентифицируют. DMARC —
   исключение: без своей записи проверяющий возьмёт политику организационного
   домена (`_dmarc.tssv85.com`), но `pass` от этого не появится. После смены
   `ABV_MAIL_FROM` проверьте домен именно из него:

   ```bash
   dig +short TXT ababawa.tssv85.com                   # v=spf1 ... -all
   dig +short TXT mail._domainkey.ababawa.tssv85.com   # v=DKIM1; ...
   dig +short TXT _dmarc.ababawa.tssv85.com            # v=DMARC1; p=none; ...
   ```

   Если ключ уже выпущен для `tssv85.com`, тот же публичный ключ можно
   опубликовать и на поддомене; в `/etc/opendkim/KeyTable` тогда добавляется
   строка `mail._domainkey.ababawa.tssv85.com ababawa.tssv85.com:mail:/путь/к/mail.private`,
   а в `SigningTable` — `*@ababawa.tssv85.com mail._domainkey.ababawa.tssv85.com`.
   Проверка перед отправкой: `opendkim-testkey -d ababawa.tssv85.com -s mail -vvv`
   (должно быть `key OK`).

Проверка: отправьте письмо на адрес из `mail-tester.com` и убедитесь, что
SPF/DKIM/DMARC «pass», и только потом тестируйте Gmail. У аутентифицированного
письма в Gmail «Show original» те же три строки, что у писем GitHub:

```
Authentication-Results: mx.google.com;
       dkim=pass header.i=@ababawa.tssv85.com header.s=mail;
       spf=pass (...) smtp.mailfrom=no-reply@ababawa.tssv85.com;
       dmarc=pass (p=NONE) header.from=ababawa.tssv85.com
```

`spf=none` и `dkim=none` означают, что записей нет по домену из `From` (пункт
5), `dkim=fail` — что подпись есть, но ключ в DNS не тот, которым подписывали.

### Внешний релей вместо своего Postfix

Если провайдер блокирует 25 порт, задайте в `/opt/abavava/.env.production`:

```dotenv
ABV_MAIL_HOST=smtp.yandex.ru
ABV_MAIL_PORT=465
ABV_MAIL_USERNAME=no-reply@abavava.tssv85.com
ABV_MAIL_PASSWORD=<пароль приложения>
ABV_MAIL_SSL=true
```

Для 587 используйте `ABV_MAIL_STARTTLS=true`. Сертификат сервера проверяется
всегда, самоподписанный не подойдёт.

### Проверка

```bash
cd /opt/abavava
docker compose --env-file .env.production --env-file .deploy.env -f compose.prod.yaml logs -f api | grep -i mail
```

При регистрации в логе появляется `mail accepted by host:port for a***@domain`,
при ошибке — `mail delivery failed (...)` с ответом SMTP. В production код в
логи не пишется никогда: в development (`ABV_ENV=development`) он виден и в
логе, и в поле `dev_code` ответа `/auth/register`.

### Отправка кода по REST

Проверить доставку, не проходя регистрацию в интерфейсе:

```bash
curl -sS -X POST https://abavava.tssv85.com/api/v1/auth/send-code \
  -H 'Content-Type: application/json' \
  -d '{"email":"anya@example.com"}'
```

Эндпоинт работает с существующим аккаунтом (неизвестный адрес → `409`), в том
числе с уже подтверждённым: так удобно проверять доставку на своём ящике. Код
из такого письма не пройдёт `/auth/verify` — адрес уже подтверждён. По умолчанию
в production эндпоинт **выключен** (`404`): `dev` действует лишь вне production.
Чтобы открыть его публично, задайте в `.env.production`:

```dotenv
ABV_EMAILVERIFICATION_SENDCODE=on
```

Это публичный триггер писем на любой неподтверждённый адрес: единственное
ограничение — rate-limit по IP (30 запросов в минуту) и пауза `resend-cooldown`
на аккаунт. Включайте осознанно.

### Временный тест почты (флаг `ABV_MAIL_TEST_ENDPOINT`)

Ручка `POST /api/v1/mail/test` генерирует код и синхронно отправляет письмо на
любой адрес, возвращая результат SMTP. Она существует, только когда включён
флаг:

```dotenv
ABV_MAIL_TEST_ENDPOINT=true
```

По умолчанию (и на VPS) флаг выключен: контроллер не создаётся, маршрут
отвечает `404`. Включайте его на время настройки `ABV_MAIL_*` и выключайте
обратно — `ABV_EMAILVERIFICATION_SENDCODE` на эту ручку больше не влияет.

```bash
curl -sS -X POST https://abavava.tssv85.com/api/v1/mail/test \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com"}'
```

Успех — `{"email":"...","code":"483920","sent":true}`. Если SMTP не отвечает или
письмо отклонено, ответ тот же, но с причиной:

```json
{"email":"you@example.com","code":"483920","sent":false,
 "error":"java.net.ConnectException: Connection refused"}
```

Флаг читается при старте: после правки `.env.production` пересоздайте контейнер
(`docker compose ... up -d api`). Код в ответе не сохраняется в БД —
`/auth/verify` его не примет.

## GitHub Actions и деплой

В репозитории откройте **Settings → Secrets and variables → Actions → New
repository secret** и добавьте:

| Secret | Значение |
| --- | --- |
| `VPS_HOST` | IP-адрес или SSH hostname сервера |
| `VPS_USER` | deploy-пользователь |
| `VPS_SSH_PRIVATE_KEY` | приватный SSH-ключ deploy-пользователя |
| `VPS_KNOWN_HOSTS` | проверенная строка SSH host key сервера |

Установите публичную часть ключа в
`/home/<deploy-user>/.ssh/authorized_keys`. Host key сохраните в
`VPS_KNOWN_HOSTS` после проверки через доверенный административный канал.
Workflow запускается при push в `main` или вручную через **Actions → Build and
deploy to VPS → Run workflow**. Он запускает backend-тесты, собирает два Docker-
образа (`abavava-api` и `abavava-web`), отправляет их в GHCR и обновляет Compose
на VPS. Оба контейнера получают тег commit SHA.

Для публикации и скачивания приватных образов workflow использует свой
краткоживущий `GITHUB_TOKEN` с разрешением `packages: write`. Он передаётся VPS
через SSH только на время run и хранится во временном Docker config, удаляемом в
завершающем шаге. Отдельный PAT для GHCR не нужен.

Проверьте `https://abavava.tssv85.com/` и
`https://abavava.tssv85.com/api/v1/readyz`. Статус контейнеров и логи:

```bash
cd /opt/abavava
docker compose --env-file .env.production --env-file .deploy.env \
  -f compose.prod.yaml ps
docker compose --env-file .env.production --env-file .deploy.env \
  -f compose.prod.yaml logs -f web api postgres
```

## Откат, данные и дополнительные настройки

В `/opt/abavava/.deploy.env` указаны полные теги обоих образов. Для отката
замените их тегами предыдущего commit SHA и выполните:

```bash
docker compose --env-file .env.production --env-file .deploy.env \
  -f compose.prod.yaml pull
docker compose --env-file .env.production --env-file .deploy.env \
  -f compose.prod.yaml up -d
```

Перед откатом проверьте совместимость старого backend с миграциями, уже
применёнными к БД. Регулярно резервируйте volume PostgreSQL `pgdata`, храните
зашифрованные копии вне VPS и проверяйте восстановление.

Android push включается отдельно: смонтируйте серверный Firebase service-account
JSON и задайте `ABV_FCM_SERVICE_ACCOUNT_FILE` для API-сервиса в Compose. Не
путайте его с Android `google-services.json`.

Coturn запускается в host network mode, чтобы корректно обслуживать ограниченный
диапазон UDP relay-портов. Deploy workflow создает случайные TURN credentials,
если они еще не заданы, и сохраняет их в закрытом `/opt/abavava/.deploy.env`.
Файл `.env.production` остается под управлением владельца и не меняется workflow.
TURN URL формируется как `turn:<ABV_DOMAIN>:3478?transport=udp`. Сервер слушает
UDP 3478 и выдает relay-порты UDP 49160–49200; TCP и TLS listener отключены.
Образ coturn закреплен на версии 4.19.0-r0. Начиная с 4.18 DTLS listener и CLI
выключены по умолчанию; старые флаги `--no-dtls`, `--no-cli` и `--no-loopback-peers`
не передаются. Loopback peers запрещены по умолчанию.
Workflow открывает эти порты в активном UFW или firewalld, если deploy-пользователю
доступен `sudo -n`. Если у VPS есть отдельный firewall/security group в панели
провайдера, там также должны быть разрешены входящие UDP 3478 и 49160–49200.

Для проверки с Android откройте WebRTC Trickle ICE в браузере телефона, укажите
TURN URL, username и credential, и убедитесь, что собирается кандидат типа
`relay`. Затем проверьте реальный звонок через приложение. Доступность UDP 3478
сама по себе еще не доказывает, что relay-диапазон открыт и медиа идет через TURN.

PostgreSQL публикуется только на `127.0.0.1:5432`; в интернет порт не открыт.
Порты `18080` и `18082` тоже доступны только с loopback.

### Подключение к БД с локальной машины

```bash
# терминал 1: туннель (локальный 5433 → 5432 на VPS)
ssh -i ~/.ssh/abavava_deploy -N -L 5433:127.0.0.1:5432 deploy@<VPS_HOST>

# терминал 2: клиент, пароль — ABV_DATABASE_PASSWORD из /opt/abavava/.env.production
psql "postgresql://abavava@127.0.0.1:5433/abavava"
```

Пароль БД лежит в `/opt/abavava/.env.production` (файл принадлежит владельцу
деплоя, `chmod 600`). Не публикуйте порт на `0.0.0.0`: Docker открывает порты
в обход UFW, и БД окажется в интернете с единственной защитой в виде пароля.
