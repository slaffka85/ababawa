package com.abavava.chat.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к таблице password_resets (см. migrations/0004_password_reset.sql).
 * В строке лежит только HMAC от кода, сам код существует в письме и в памяти
 * процесса на время выпуска.
 */
@Repository
public class PasswordResetRepository {

    private static final String COLUMNS =
            "id, user_id, code_hash, attempts, expires_at, created_at";

    private static final RowMapper<Reset> MAPPER = (rs, rowNum) -> new Reset(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("code_hash"),
            rs.getInt("attempts"),
            instant(rs, "expires_at"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public PasswordResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Выпущенный код (без plain-text значения). */
    public record Reset(
            UUID id,
            UUID userId,
            String codeHash,
            int attempts,
            Instant expiresAt,
            Instant createdAt) {
    }

    public Reset insert(UUID userId, String codeHash, Instant expiresAt, String ip) {
        return jdbc.sql("INSERT INTO password_resets (user_id, code_hash, expires_at, ip)\n"
                        + "VALUES (?, ?, ?, ?)\n"
                        + "RETURNING " + COLUMNS)
                .param(userId)
                .param(codeHash)
                .param(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .param(ip)
                .query(MAPPER)
                .single();
    }

    /** Последний неиспользованный и неистёкший код пользователя. */
    public Optional<Reset> active(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + "\n"
                        + "FROM password_resets\n"
                        + "WHERE user_id = ?\n"
                        + "  AND consumed_at IS NULL\n"
                        + "  AND expires_at > now()\n"
                        + "ORDER BY created_at DESC\n"
                        + "LIMIT 1")
                .param(userId)
                .query(MAPPER)
                .optional();
    }

    /** Считает неудачную попытку ввода; возвращает накопленное число попыток. */
    public int registerAttempt(UUID resetId) {
        return jdbc.sql("UPDATE password_resets\n"
                        + "   SET attempts = attempts + 1\n"
                        + " WHERE id = ?\n"
                        + " RETURNING attempts")
                .param(resetId)
                .query(Integer.class)
                .single();
    }

    /** Гасит конкретный код (успешный сброс или исчерпанные попытки). */
    public void consume(UUID resetId) {
        jdbc.sql("UPDATE password_resets SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL")
                .param(resetId)
                .update();
    }

    /** Гасит все активные коды пользователя: действующим остаётся только свежий. */
    public void consumeAllForUser(UUID userId) {
        jdbc.sql("UPDATE password_resets SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL")
                .param(userId)
                .update();
    }

    /** Момент выпуска последнего кода — основа паузы между отправками. */
    public Instant lastCreatedAt(UUID userId) {
        record Row(OffsetDateTime value) {
        }
        return jdbc.sql("SELECT max(created_at) AS last_created FROM password_resets WHERE user_id = ?")
                .param(userId)
                .query((rs, rowNum) -> new Row(rs.getObject("last_created", OffsetDateTime.class)))
                .optional()
                .map(Row::value)
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    /** Сколько кодов выпущено пользователю после момента `since`. */
    public int countSince(UUID userId, Instant since) {
        Integer count = jdbc.sql("SELECT count(*) FROM password_resets WHERE user_id = ? AND created_at > ?")
                .param(userId)
                .param(OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Удаляет давно протухшие строки; вызывается при выпуске нового кода. */
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM password_resets WHERE expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
