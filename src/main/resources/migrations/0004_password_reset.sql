-- 0004_password_reset.sql — сброс пароля одноразовым кодом
--
-- Отдельная таблица от email_verifications: код сброса нельзя предъявить в
-- /auth/verify, а код подтверждения — в /auth/password/reset (разные таблицы
-- и разные сценарии). В БД лежит только HMAC-SHA256 от «<user_id>:<code>» с
-- серверным секретом: утечка таблицы не позволяет подобрать код офлайн,
-- а привязка к user_id не даёт переиспользовать код другого аккаунта.

CREATE TABLE password_resets (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   TEXT        NOT NULL,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    ip          TEXT,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Активный код пользователя: последний неиспользованный и неистёкший.
CREATE INDEX password_resets_user_idx
    ON password_resets (user_id, created_at DESC);

-- Очистка протухших записей при выпуске нового кода.
CREATE INDEX password_resets_expires_idx
    ON password_resets (expires_at);
