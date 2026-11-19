package com.abavava.chat.auth;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.User;
import com.abavava.chat.security.JwtService;
import com.abavava.chat.security.Passwords;
import com.abavava.chat.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Сценарии аутентификации: регистрация, подтверждение email, вход, ротация
 * refresh-токена, выход из сессии.
 *
 * <p>Регистрация больше не выдаёт токены: аккаунт создаётся неподтверждённым,
 * на почту уходит код, а пара токенов появляется только после успешного
 * {@code /auth/verify} (или обычного {@code /auth/login} подтверждённого
 * аккаунта).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72;
    private static final int MAX_LOGIN_LENGTH = 254;
    private static final int MAX_DISPLAY_NAME = 64;

    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final JwtService jwt;
    private final AbvProperties props;
    private final EmailVerificationService verifications;

    public record SessionMeta(String userAgent, String ip) {
    }

    /** Выданная пара токенов вместе с профилем (JSON: camelCase → snake_case). */
    public record AuthResult(
            User user,
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") int expiresIn
    ) {
    }

    /**
     * Ответ на регистрацию: токенов нет, аккаунт ждёт ввода кода из письма.
     * {@code devCode} заполняется только при включённом echo-code вне production.
     */
    public record RegisterPending(
            @com.fasterxml.jackson.annotation.JsonProperty("verification_id") java.util.UUID verificationId,
            @com.fasterxml.jackson.annotation.JsonProperty("email") String email,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") int expiresIn,
            @com.fasterxml.jackson.annotation.JsonProperty("resend_in") int resendIn,
            @com.fasterxml.jackson.annotation.JsonProperty("email_sent") boolean emailSent,
            @com.fasterxml.jackson.annotation.JsonProperty("dev_code") String devCode
    ) {
    }

    public record RegisterInput(String email, String displayName, String password) {
    }

    public record LoginInput(String email, String password) {
    }

    public record VerifyInput(String email, String code) {
    }

    public RegisterPending register(RegisterInput input, SessionMeta meta) {
        String email = Emails.normalize(input.email());
        String name = normalizeDisplayName(input.displayName());
        //validatePassword(input.password());
        String hash = Passwords.hash(input.password());
        User account = users.createAccount(email, name, hash);
        EmailVerificationService.Issued issued = verifications.issue(account, meta.ip());
        return new RegisterPending(
                issued.verificationId(),
                Emails.mask(email),
                (int) issued.expiresIn().toSeconds(),
                (int) issued.resendIn().toSeconds(),
                issued.emailQueued(),
                props.echoVerificationCode() ? issued.code() : null);
    }

    /** Проверка кода из письма: сразу выдаём пару токенов. */
    public AuthResult verifyEmail(VerifyInput input, SessionMeta meta) {
        User account = verifications.verify(input.email(), input.code());
        return issue(account, meta);
    }

    /** Повторная отправка кода (пауза и лимиты — в EmailVerificationService). */
    public EmailVerificationService.Resent resendVerification(String email, String ip) {
        return verifications.resend(email, ip);
    }

    /**
     * Ручная отправка кода через REST: тот же код, что уходит при регистрации,
     * но по явному вызову (dev-инструмент или публичный режим — см.
     * abv.email-verification.send-code).
     */
    public EmailVerificationService.Resent sendVerificationCode(String email, String ip) {
        return verifications.sendCode(email, ip);
    }

    public AuthResult login(LoginInput input, SessionMeta meta) {
        String login = normalizeLogin(input.email());
        UserRepository.Account account = users.accountByEmail(login);
        if (account == null) {
            Passwords.compareWithDummy(input.password());
            throw AbvException.unauthorized("invalid email or password");
        }
        if (!Passwords.matches(account.passwordHash(), input.password())) {
            throw AbvException.unauthorized("invalid email or password");
        }
        if (!account.emailVerified()) {
            // Пароль верный, поэтому адрес можно не скрывать: клиент его и так знает.
            throw new AbvException(AbvException.Code.FORBIDDEN, "email is not verified",
                    Map.of("reason", "email_not_verified", "email", account.user().email()), null);
        }
        return issue(account.user(), meta);
    }

    public AuthResult refresh(String refreshToken, SessionMeta meta) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw AbvException.invalidField("refresh_token", "refresh_token is required");
        }
        String oldHash = Passwords.hashToken(refreshToken);
        String newToken = Passwords.randomToken(32);
        OffsetDateTime expiresAt = expiresAt(props.refreshTtl());
        java.util.UUID userId;
        try {
            userId = tokens.rotate(oldHash, Passwords.hashToken(newToken), meta.userAgent(), meta.ip(), expiresAt);
        } catch (RefreshTokenRepository.TokenReuseException reuse) {
            log.warn("refresh token reuse detected: user_id={}", reuse.userId());
            tokens.revokeAllForUser(reuse.userId());
            throw AbvException.unauthorized("session compromised, please sign in again");
        }
        User account = users.byId(userId);
        return result(account, newToken);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        tokens.revoke(Passwords.hashToken(refreshToken));
    }

    public java.util.UUID verifyAccess(String raw) {
        return jwt.verifyAccessToken(raw);
    }

    public User authenticate(String raw) {
        return users.byId(verifyAccess(raw));
    }

    private AuthResult issue(User account, SessionMeta meta) {
        String refresh = Passwords.randomToken(32);
        tokens.insert(account.id(), Passwords.hashToken(refresh), meta.userAgent(), meta.ip(),
                expiresAt(props.refreshTtl()));
        return result(account, refresh);
    }

    private AuthResult result(User account, String refreshToken) {
        String access = jwt.issueAccessToken(account.id());
        return new AuthResult(account, access, refreshToken, (int) props.accessTtl().getSeconds());
    }

    private static OffsetDateTime expiresAt(Duration ttl) {
        return OffsetDateTime.now(ZoneOffset.UTC).plus(ttl);
    }

    // ----------------------------------------------------------- валидация

    /** Вход не проверяет формат email: демо-аккаунты могут иметь короткий логин. */
    private static String normalizeLogin(String raw) {
        String login = raw == null ? "" : raw.trim().toLowerCase();
        if (login.isEmpty()) {
            throw AbvException.unauthorized("invalid email or password");
        }
        if (login.length() > MAX_LOGIN_LENGTH) {
            throw AbvException.unauthorized("invalid email or password");
        }
        return login;
    }

    private static String normalizeDisplayName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw AbvException.invalidField("display_name", "display_name is required");
        }
        if (name.codePointCount(0, name.length()) > MAX_DISPLAY_NAME) {
            throw AbvException.invalidField("display_name",
                    "display_name must be at most " + MAX_DISPLAY_NAME + " characters");
        }
        return name;
    }

    private static void validatePassword(String password) {
        String value = password == null ? "" : password;
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < MIN_PASSWORD_LENGTH) {
            throw AbvException.invalidField("password",
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (bytes > MAX_PASSWORD_LENGTH) {
            throw AbvException.invalidField("password",
                    "password must be at most " + MAX_PASSWORD_LENGTH + " characters");
        }
        if (value.isBlank()) {
            throw AbvException.invalidField("password", "password must not be blank");
        }
    }
}
