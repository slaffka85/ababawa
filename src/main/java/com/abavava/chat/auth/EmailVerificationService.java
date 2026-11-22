package com.abavava.chat.auth;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.User;
import com.abavava.chat.mail.VerificationMailer;
import com.abavava.chat.security.EmailCodes;
import com.abavava.chat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Подтверждение email одноразовым кодом: выпуск, повторная отправка, проверка.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>действующим считается только последний код — предыдущие гасятся;</li>
 *   <li>код живёт {@code abv.email-verification.code-ttl} и умирает после
 *       {@code max-attempts} неудачных вводов;</li>
 *   <li>повторная отправка — не чаще {@code resend-cooldown} и не больше
 *       {@code max-resends} писем за {@code resend-window};</li>
 *   <li>для несуществующего адреса ответ такой же, как для существующего
 *       (кроме случаев, когда клиент уже знает пароль).</li>
 * </ul>
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** Режимы {@code abv.email-verification.send-code}. */
    public static final String MODE_OFF = "off";
    public static final String MODE_DEV = "dev";
    public static final String MODE_ON = "on";

    private final EmailVerificationRepository codes;
    private final UserRepository users;
    private final VerificationMailer mailer;
    private final AbvProperties props;

    public EmailVerificationService(EmailVerificationRepository codes, UserRepository users,
                                    VerificationMailer mailer, AbvProperties props) {
        this.codes = codes;
        this.users = users;
        this.mailer = mailer;
        this.props = props;
    }

    /** Выпущенный код. {@code code} отдаётся наружу только в development. */
    public record Issued(UUID verificationId, String code, Duration expiresIn, Duration resendIn, boolean emailQueued) {
    }

    /** Результат повторной отправки. */
    public record Resent(Duration expiresIn, Duration resendIn, boolean emailQueued, String devCode) {
    }

    /** Новый код для только что созданного (или ещё не подтверждённого) аккаунта. */
    public Issued issue(User user, String ip) {
        AbvProperties.EmailVerification config = props.emailVerification();
        Instant now = Instant.now();

        // Действующим остаётся только свежий код: старые гасим до вставки.
        codes.consumeAllForUser(user.id());

        String code = EmailCodes.generate(config.codeLength());
        Instant expiresAt = now.plus(config.codeTtl());
        EmailVerificationRepository.Verification saved = codes.insert(
                user.id(), user.email(), EmailCodes.hash(pepper(), user.id(), code), expiresAt, ip);
        codes.purgeExpiredBefore(now.minus(config.codeTtl()));

        boolean queued = mailer.sendCode(user.nickname(), user.email(), code);
        if (props.echoVerificationCode()) {
            log.warn("development mode: verification code {} for {} (not logged in production)",
                    code, Emails.mask(user.email()));
        } else {
            log.info("verification code issued for user_id={} expires_at={}", user.id(), expiresAt);
        }
        if (!queued) {
            log.warn("verification mail was not queued for user_id={} (abv.mail.enabled=false or queue is full)",
                    user.id());
        }
        return new Issued(saved.id(), code, config.codeTtl(), config.resendCooldown(), queued);
    }

    /**
     * Повторная отправка по адресу. Для неизвестного адреса отвечаем так же,
     * как для известного, но письмо не отправляем (защита от перебора базы).
     */
    public Resent resend(String rawEmail, String ip) {
        AbvProperties.EmailVerification config = props.emailVerification();
        String email = Emails.normalize(rawEmail);
        UserRepository.Account account = users.accountByEmail(email);
        if (account == null) {
            log.info("resend requested for unknown address {}", Emails.mask(email));
            return new Resent(config.codeTtl(), config.resendCooldown(), false, null);
        }
        if (account.emailVerified()) {
            throw alreadyVerified();
        }

        enforceCooldown(account.user().id(), config);
        enforceHourlyCap(account.user().id(), config);

        Issued issued = issue(account.user(), ip);
        return new Resent(issued.expiresIn(), issued.resendIn(), issued.emailQueued(),
                props.echoVerificationCode() ? issued.code() : null);
    }

    /**
     * Ручная отправка кода через REST: {@code POST /auth/send-code}.
     *
     * <p>Работает только с существующим аккаунтом: адреса нет — 409
     * {@code email_not_registered} (тот, кто зовёт эндпоинт вручную, и так знает
     * адрес). Подтверждённый адрес письмо тоже получает — это ручной инструмент
     * проверки доставки; код из такого письма не пройдёт {@code /auth/verify}
     * (адрес уже подтверждён), но письмо и его оформление проверить можно.
     *
     * <p>Режим задаётся {@code abv.email-verification.send-code}: {@code off} —
     * 404, {@code dev} — только вне production, без паузы и часового лимита
     * (проверка SMTP из REST-клиента), {@code on} — публичный режим с теми же
     * паузами и лимитами, что у {@code /auth/resend}.
     */
    public Resent sendCode(String rawEmail, String ip) {
        AbvProperties.EmailVerification config = props.emailVerification();
        boolean development = isDevelopmentTool(config);
        if (!development && !MODE_ON.equalsIgnoreCase(config.sendCode())) {
            throw AbvException.notFound("no such endpoint: /api/v1/auth/send-code");
        }

        String email = Emails.normalize(rawEmail);
        UserRepository.Account account = users.accountByEmail(email);
        if (account == null) {
            throw new AbvException(AbvException.Code.CONFLICT, "email is not registered",
                    Map.of("field", "email", "reason", "email_not_registered"), null);
        }
        if (account.emailVerified()) {
            log.info("manual mail requested for already verified user_id={}", account.user().id());
        }

        if (development) {
            // Dev-инструмент: письмо уходит сразу, пауза и лимит обходятся.
            Issued issued = issue(account.user(), ip);
            log.info("manual verification mail requested for user_id={} (dev mode)", account.user().id());
            return new Resent(issued.expiresIn(), Duration.ZERO, issued.emailQueued(),
                    props.echoVerificationCode() ? issued.code() : null);
        }

        enforceCooldown(account.user().id(), config);
        enforceHourlyCap(account.user().id(), config);
        Issued issued = issue(account.user(), ip);
        log.info("manual verification mail requested for user_id={}", account.user().id());
        return new Resent(issued.expiresIn(), issued.resendIn(), issued.emailQueued(),
                props.echoVerificationCode() ? issued.code() : null);
    }

    private void enforceCooldown(UUID userId, AbvProperties.EmailVerification config) {
        Instant lastCreated = codes.lastCreatedAt(userId);
        if (lastCreated == null) {
            return;
        }
        Duration sinceLast = Duration.between(lastCreated, Instant.now());
        if (sinceLast.compareTo(config.resendCooldown()) < 0) {
            long waitSeconds = Math.max(1L, config.resendCooldown().minus(sinceLast).toSeconds());
            throw rateLimited("verification email was sent recently, try again later",
                    Map.of("field", "email", "resend_in", waitSeconds));
        }
    }

    private void enforceHourlyCap(UUID userId, AbvProperties.EmailVerification config) {
        int sentInWindow = codes.countSince(userId, Instant.now().minus(config.resendWindow()));
        if (sentInWindow >= config.maxResends()) {
            throw rateLimited("too many verification emails, try again later",
                    Map.of("retry_in", config.resendWindow().toSeconds()));
        }
    }

    private static AbvException alreadyVerified() {
        return new AbvException(AbvException.Code.CONFLICT, "email is already verified",
                Map.of("reason", "email_already_verified"), null);
    }

    /** Dev-инструмент: `dev` и окружение не production. */
    private boolean isDevelopmentTool(AbvProperties.EmailVerification config) {
        return MODE_DEV.equalsIgnoreCase(config.sendCode()) && !props.isProduction();
    }

    /**
     * Проверяет код и помечает адрес подтверждённым.
     *
     * @return подтверждённый пользователь (для выдачи токенов)
     */
    public User verify(String rawEmail, String rawCode) {
        AbvProperties.EmailVerification config = props.emailVerification();
        String email = Emails.normalize(rawEmail);
        String code = EmailCodes.digitsOnly(rawCode);

        UserRepository.Account account = users.accountByEmail(email);
        if (account == null) {
            // Тот же ответ, что и на неверный код: не подтверждаем существование адреса.
            throw invalidCode(-1);
        }
        UUID userId = account.user().id();
        if (account.emailVerified()) {
            throw alreadyVerified();
        }
        if (!EmailCodes.isWellFormed(code, config.codeLength())) {
            throw invalidCode(config.maxAttempts());
        }

        EmailVerificationRepository.Verification active = codes.active(userId).orElseThrow(() ->
                new AbvException(AbvException.Code.VALIDATION_FAILED,
                        "verification code has expired, request a new one",
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
        User verified = users.markEmailVerified(userId);
        log.info("email verified for user_id={}", userId);
        return verified;
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
        return new AbvException(AbvException.Code.VALIDATION_FAILED, "invalid verification code", details, null);
    }

    private static AbvException rateLimited(String message, Map<String, Object> details) {
        return new AbvException(AbvException.Code.RATE_LIMITED, message, details, null);
    }
}
