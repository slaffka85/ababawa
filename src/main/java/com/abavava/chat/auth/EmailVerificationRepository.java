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
 * Доступ к таблице email_verifications (см. migrations/0003_email_verification.sql).
 * В строке лежит только HMAC от кода, сам код существует в письме и в памяти
 * процесса на время выпуска.
 */
@Repository
public class EmailVerificationRepository {

    private static final String COLUMNS =
            "id, user_id, email, code_hash, attempts, expires_at, created_at";

    private static final RowMapper<Verification> MAPPER = (rs, rowNum) -> new Verification(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("email"),
            rs.getString("code_hash"),
            rs.getInt("attempts"),
            instant(rs, "expires_at"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public EmailVerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Выпущенный код (без plain-text значения). */
    public record Verification(
            UUID id,
            UUID userId,
            String email,
            String codeHash,
            int attempts,
            Instant expiresAt,
            Instant createdAt) {
    }

    public Verification insert(UUID userId, String email, String codeHash, Instant expiresAt, String ip) {
        return jdbc.sql("INSERT INTO email_verifications (user_id, email, code_hash, expires_at, ip)\n"
                        + "VALUES (?, ?, ?, ?, ?)\n"
                        + "RETURNING " + COLUMNS)
                .param(userId)
                .param(email)
                .param(codeHash)
                .param(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .param(ip)
                .query(MAPPER)
                .single();
    }

    /** Последний неиспользованный и неистёкший код пользователя. */
    public Optional<Verification> active(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + "\n"
                        + "FROM email_verifications\n"
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
    public int registerAttempt(UUID verificationId) {
        return jdbc.sql("UPDATE email_verifications\n"
                        + "   SET attempts = attempts + 1\n"
                        + " WHERE id = ?\n"
                        + " RETURNING attempts")
                .param(verificationId)
                .query(Integer.class)
                .single();
    }

    /** Гасит конкретный код (успешный вход или исчерпанные попытки). */
    public void consume(UUID verificationId) {
        jdbc.sql("UPDATE email_verifications SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL")
                .param(verificationId)
                .update();
    }

    /** Гасит все активные коды пользователя: действующим остаётся только свежий. */
    public void consumeAllForUser(UUID userId) {
        jdbc.sql("UPDATE email_verifications SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL")
                .param(userId)
                .update();
    }

    /** Момент выпуска последнего кода — основа паузы между отправками. */
    public Instant lastCreatedAt(UUID userId) {
        record Row(OffsetDateTime value) {
        }
        return jdbc.sql("SELECT max(created_at) AS last_created FROM email_verifications WHERE user_id = ?")
                .param(userId)
                .query((rs, rowNum) -> new Row(rs.getObject("last_created", OffsetDateTime.class)))
                .optional()
                .map(Row::value)
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    /** Сколько кодов выпущено пользователю после момента `since`. */
    public int countSince(UUID userId, Instant since) {
        Integer count = jdbc.sql("SELECT count(*) FROM email_verifications WHERE user_id = ? AND created_at > ?")
                .param(userId)
                .param(OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Удаляет давно протухшие строки; вызывается при выпуске нового кода. */
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM email_verifications WHERE expires_at < ?")
                .param(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
