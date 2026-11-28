package com.abavava.chat.auth;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.User;
import com.abavava.chat.mail.VerificationMailer;
import com.abavava.chat.observability.BusinessMetrics;
import com.abavava.chat.security.JwtService;
import com.abavava.chat.security.Passwords;
import com.abavava.chat.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Метрики сценариев авторизации на реальном {@link AuthService} с подставными
 * репозиториями: успех, отказ и причина отказа попадают в метки счётчиков.
 */
class AuthServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final AuthService.SessionMeta META = new AuthService.SessionMeta("test-agent", "203.0.113.7");

    private SimpleMeterRegistry registry;
    private FakeUsers users;
    private FakeTokens tokens;
    private AuthService service;

    @BeforeEach
    void setUp() throws Exception {
        AbvProperties props = loadProps();
        registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);
        users = new FakeUsers(new UserRepository.Account(user(), Passwords.hash(PASSWORD), true));
        tokens = new FakeTokens();
        JwtService jwt = new JwtService(
                "0123456789abcdef0123456789abcdef", props.accessTtl(), new ObjectMapper());
        FakeMailer mailer = new FakeMailer();
        EmailVerificationService verifications =
                new EmailVerificationService(new FakeCodes(), users, mailer, props, metrics);
        PasswordResetService passwordResets =
                new PasswordResetService(new FakeResets(), users, tokens, mailer, props, metrics);
        service = new AuthService(users, tokens, jwt, props, verifications, passwordResets, metrics);
    }

    @Test
    void countsSuccessfulRegistrationAndIssuedCode() {
        AuthService.RegisterPending pending = service.register(
                new AuthService.RegisterInput("new@example.com", "Аня", "password-1"), META);

        assertThat(pending.emailSent()).isTrue();
        assertThat(counter("ababawa.auth.registration", "result", "success", "reason", "none"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.auth.otp.requests",
                "purpose", "registration", "result", "success", "reason", "none")).isEqualTo(1.0);
    }

    @Test
    void countsRegistrationFailureReasons() {
        users.conflictOnCreate = true;
        assertThatThrownBy(() -> service.register(
                new AuthService.RegisterInput("new@example.com", "Аня", "password-1"), META))
                .isInstanceOf(AbvException.class);

        users.conflictOnCreate = false;
        assertThatThrownBy(() -> service.register(
                new AuthService.RegisterInput("other@example.com", "   ", "password-1"), META))
                .isInstanceOf(AbvException.class);

        assertThat(counter("ababawa.auth.registration", "result", "failure", "reason", "email_taken"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.auth.registration", "result", "failure", "reason", "invalid_input"))
                .isEqualTo(1.0);
        assertThat(registry.find("ababawa.auth.registration")
                .tags("result", "success").counter()).isNull();
    }

    @Test
    void countsLoginSuccessAndFailureReasons() {
        service.login(new AuthService.LoginInput(EMAIL, PASSWORD), META);

        assertThatThrownBy(() -> service.login(new AuthService.LoginInput(EMAIL, "wrong-password"), META))
                .isInstanceOf(AbvException.class);

        users.account = new UserRepository.Account(user(), users.account.passwordHash(), false);
        assertThatThrownBy(() -> service.login(new AuthService.LoginInput(EMAIL, PASSWORD), META))
                .isInstanceOf(AbvException.class);

        assertThat(counter("ababawa.auth.logins", "result", "success", "reason", "none"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.auth.logins", "result", "failure", "reason", "invalid_credentials"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.auth.logins", "result", "failure", "reason", "email_not_verified"))
                .isEqualTo(1.0);
    }

    @Test
    void countsRefreshRotationAndTokenReuse() {
        service.refresh("valid-refresh-token", META);

        tokens.reuse = true;
        assertThatThrownBy(() -> service.refresh("stolen-refresh-token", META))
                .isInstanceOf(AbvException.class);

        tokens.reuse = false;
        tokens.unauthorized = true;
        assertThatThrownBy(() -> service.refresh("unknown-refresh-token", META))
                .isInstanceOf(AbvException.class);

        assertThat(counter("ababawa.auth.refresh", "result", "success", "reason", "none"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.auth.refresh", "result", "failure", "reason", "token_reuse"))
                .isEqualTo(1.0);
        assertThat(counter("ababawa.auth.refresh", "result", "failure", "reason", "invalid_token"))
                .isEqualTo(1.0);
        // Повторное использование токена отзывает все сессии пользователя.
        assertThat(tokens.revokedFor).isEqualTo(USER_ID);
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static User user() {
        return new User(USER_ID, EMAIL, "Аня", null, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private static AbvProperties loadProps() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        ConfigurationPropertySource source = ConfigurationPropertySources.from(sources).iterator().next();
        return new Binder(source).bind("abv", Bindable.of(AbvProperties.class))
                .orElseThrow(() -> new AssertionError("abv section is missing in application.yml"));
    }

    /** Подставной UserRepository: один аккаунт в памяти. */
    private static final class FakeUsers extends UserRepository {

        private UserRepository.Account account;
        private boolean conflictOnCreate;

        FakeUsers(UserRepository.Account account) {
            super(null);
            this.account = account;
        }

        @Override
        public User createAccount(String email, String nickname, String passwordHash) {
            if (conflictOnCreate) {
                throw AbvException.conflict("email is already registered");
            }
            return new User(UUID.randomUUID(), email, nickname, null,
                    Instant.now(), null);
        }

        @Override
        public Account accountByEmail(String login) {
            return account;
        }

        @Override
        public User byId(UUID id) {
            return account != null && account.user().id().equals(id) ? account.user() : user();
        }

        @Override
        public User markEmailVerified(UUID id) {
            return account.user();
        }

        @Override
        public void updatePassword(UUID id, String passwordHash) {
            // не требуется сценариями входа
        }
    }

    /** Подставной репозиторий refresh-токенов: успешная ротация и два отказа. */
    private static final class FakeTokens extends RefreshTokenRepository {

        private boolean reuse;
        private boolean unauthorized;
        private UUID revokedFor;

        FakeTokens() {
            super(null, null);
        }

        @Override
        public void insert(UUID userId, String tokenHash, String userAgent, String ip, OffsetDateTime expiresAt) {
            // выдача токена при входе: для метрик не важна
        }

        @Override
        public UUID rotate(String oldHash, String newHash, String userAgent, String ip,
                           OffsetDateTime expiresAt) {
            if (reuse) {
                throw new TokenReuseException(USER_ID);
            }
            if (unauthorized) {
                throw AbvException.unauthorized("refresh token is not recognized");
            }
            return USER_ID;
        }

        @Override
        public void revokeAllForUser(UUID userId) {
            this.revokedFor = userId;
        }
    }

    /** Подставной репозиторий кодов подтверждения: код не нужен, важны вызовы. */
    private static final class FakeCodes extends EmailVerificationRepository {

        FakeCodes() {
            super(null);
        }

        @Override
        public Verification insert(UUID userId, String email, String codeHash, Instant expiresAt, String ip) {
            return new Verification(UUID.randomUUID(), userId, email, codeHash, 0, expiresAt, Instant.now());
        }

        @Override
        public Optional<Verification> active(UUID userId) {
            return Optional.empty();
        }

        @Override
        public int registerAttempt(UUID verificationId) {
            return 1;
        }

        @Override
        public void consume(UUID verificationId) {
            // нет активного кода в этом сценарии
        }

        @Override
        public void consumeAllForUser(UUID userId) {
            // нет активного кода в этом сценарии
        }

        @Override
        public Instant lastCreatedAt(UUID userId) {
            return null;
        }

        @Override
        public int countSince(UUID userId, Instant since) {
            return 0;
        }

        @Override
        public int purgeExpiredBefore(Instant cutoff) {
            return 0;
        }
    }

    /** Заглушка репозитория кодов сброса пароля: сценариями входа не используется. */
    private static final class FakeResets extends PasswordResetRepository {

        FakeResets() {
            super(null);
        }
    }

    /** Фейковый отправщик: письмо «принято» без SMTP. */
    private static final class FakeMailer extends VerificationMailer {

        FakeMailer() {
            super(null, null, null, null);
        }

        @Override
        public boolean sendCode(String nickname, String email, String code) {
            return true;
        }

        @Override
        public boolean sendPasswordReset(String nickname, String email, String code) {
            return true;
        }
    }
}
