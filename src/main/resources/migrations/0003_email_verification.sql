-- 0003_email_verification.sql — подтверждение email одноразовым кодом
--
-- users.email_verified_at: NULL — адрес не подтверждён, вход запрещён (403
-- email_not_verified). Существующие аккаунты (демо-сид, локальные тесты)
-- считаем подтверждёнными: иначе после миграции они не смогут войти.

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

-- ---------------------------------------------------- email_verifications
-- Одноразовые коды. В БД лежит только HMAC-SHA256 от «<user_id>:<code>» с
-- серверным секретом: утечка таблицы не позволяет подобрать код офлайн,
-- а привязка к user_id не даёт переиспользовать код другого аккаунта.
CREATE TABLE email_verifications (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email       TEXT        NOT NULL,
    code_hash   TEXT        NOT NULL,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    ip          TEXT,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Активный код пользователя: последний неиспользованный и не истёкший.
CREATE INDEX email_verifications_user_idx
    ON email_verifications (user_id, created_at DESC);

-- Очистка протухших записей при выпуске нового кода.
CREATE INDEX email_verifications_expires_idx
    ON email_verifications (expires_at);
