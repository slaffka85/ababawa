package com.abavava.chat.auth;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.User;
import com.abavava.chat.mail.VerificationMailer;
import com.abavava.chat.security.EmailCodes;
import com.abavava.chat.security.Passwords;
import com.abavava.chat.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сброс пароля по одноразовому коду из письма.
 *
 * <p>Сценарий: {@code POST /auth/password/forgot} выпускает код и отправляет
 * письмо, {@code POST /auth/password/reset} проверяет код и меняет пароль.
 * Коды и лимиты те же, что у подтверждения email ({@code abv.email-verification}):
 * длина, {@code code-ttl}, {@code max-attempts}, пауза и потолок отправок.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>для неизвестного адреса ответ такой же, как для известного, но письмо
 *       не отправляется (защита от перебора базы);</li>
 *   <li>код живёт {@code code-ttl} и умирает после {@code max-attempts} неудач;</li>
 *   <li>успешный сброс гасит все коды пользователя и отзывает все refresh-токены:
 *       старые сессии должны перелогиниться.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetRepository codes;
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final VerificationMailer mailer;
    private final AbvProperties props;

    /** Ответ на запрос кода: нейтральный, без признаков существования адреса. */
    public record Requested(String email, Duration expiresIn, Duration resendIn, String devCode) {
    }

    public Requested request(String rawEmail, String ip) {
        AbvProperties.EmailVerification config = props.emailVerification();
        String email = Emails.normalize(rawEmail);
        UserRepository.Account account = users.accountByEmail(email);
        if (account == null) {
            log.info("password reset requested for unknown address {}", Emails.mask(email));
            return new Requested(Emails.mask(email), config.codeTtl(), config.resendCooldown(), null);
        }

        UUID userId = account.user().id();
        enforceCooldown(userId, config);
        enforceHourlyCap(userId, config);

        Instant now = Instant.now();
        // Действующим остаётся только свежий код.
        codes.consumeAllForUser(userId);
        String code = EmailCodes.generate(config.codeLength());
        Instant expiresAt = now.plus(config.codeTtl());
        codes.insert(userId, EmailCodes.hash(pepper(), userId, code), expiresAt, ip);
        codes.purgeExpiredBefore(now.minus(config.codeTtl()));

        boolean queued = mailer.sendPasswordReset(account.user().nickname(), email, code);
        if (props.echoVerificationCode()) {
            log.warn("development mode: password reset code {} for {} (not logged in production)",
                    code, Emails.mask(email));
        } else {
            log.info("password reset code issued for user_id={} expires_at={}", userId, expiresAt);
        }
        if (!queued) {
            log.warn("password reset mail was not queued for user_id={} (abv.mail.enabled=false or queue is full)",
                    userId);
        }
        return new Requested(Emails.mask(email), config.codeTtl(), config.resendCooldown(),
                props.echoVerificationCode() ? code : null);
    }

    /** Проверяет код и меняет пароль; старые сессии отзываются. */
    public User reset(String rawEmail, String rawCode, String rawPassword) {
        AbvProperties.EmailVerification config = props.emailVerification();
        AuthService.validatePassword(rawPassword);

        String email = Emails.normalize(rawEmail);
        String code = EmailCodes.digitsOnly(rawCode);

        UserRepository.Account account = users.accountByEmail(email);
        if (account == null) {
            // Тот же ответ, что и на неверный код: не подтверждаем существование адреса.
            throw invalidCode(-1);
        }
        UUID userId = account.user().id();
        if (!EmailCodes.isWellFormed(code, config.codeLength())) {
            throw invalidCode(config.maxAttempts());
        }

        PasswordResetRepository.Reset active = codes.active(userId).orElseThrow(() ->
                new AbvException(AbvException.Code.VALIDATION_FAILED,
                        "password reset code has expired, request a new one",
                        Map.of("field", "code", "reason", "expired"), null));

        if (active.attempts() >= config.maxAttempts()) {
            codes.consume(active.id());
            throw new AbvException(AbvException.Code.VALIDATION_FAILED,
                    "too many attempts, request a new code",
                    Map.of("field", "code", "reason", "too_many_attempts"), null);
        }

        if (!EmailCodes.matches(pepper(), userId, code, active.codeHash())) {
            int attempts = codes.registerAttempt(active.id());
            int attemptsLeft = Math.max(0, config.maxAttempts() - attempts);
            if (attemptsLeft == 0) {
                codes.consume(active.id());
            }
            throw invalidCode(attemptsLeft);
        }

        codes.consume(active.id());
        codes.consumeAllForUser(userId);
        users.updatePassword(userId, Passwords.hash(rawPassword));
        tokens.revokeAllForUser(userId);
        log.info("password reset for user_id={}; refresh tokens revoked", userId);
        return account.user();
    }

    private void enforceCooldown(UUID userId, AbvProperties.EmailVerification config) {
        Instant lastCreated = codes.lastCreatedAt(userId);
        if (lastCreated == null) {
            return;
        }
        Duration sinceLast = Duration.between(lastCreated, Instant.now());
        if (sinceLast.compareTo(config.resendCooldown()) < 0) {
            long waitSeconds = Math.max(1L, config.resendCooldown().minus(sinceLast).toSeconds());
            throw rateLimited("password reset email was sent recently, try again later",
                    Map.of("field", "email", "resend_in", waitSeconds));
        }
    }

    private void enforceHourlyCap(UUID userId, AbvProperties.EmailVerification config) {
        int sentInWindow = codes.countSince(userId, Instant.now().minus(config.resendWindow()));
        if (sentInWindow >= config.maxResends()) {
            throw rateLimited("too many password reset emails, try again later",
                    Map.of("retry_in", config.resendWindow().toSeconds()));
        }
    }

    private String pepper() {
        return props.jwtSecret();
    }

    private static AbvException invalidCode(int attemptsLeft) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "code");
        if (attemptsLeft >= 0) {
            details.put("attempts_left", attemptsLeft);
        }
        return new AbvException(AbvException.Code.VALIDATION_FAILED, "invalid password reset code", details, null);
    }

    private static AbvException rateLimited(String message, Map<String, Object> details) {
        return new AbvException(AbvException.Code.RATE_LIMITED, message, details, null);
    }
}
