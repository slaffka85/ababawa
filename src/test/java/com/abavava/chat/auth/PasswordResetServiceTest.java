package com.abavava.chat.auth;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.User;
import com.abavava.chat.mail.VerificationMailer;
import com.abavava.chat.observability.BusinessMetrics;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сброс пароля без БД и SMTP: подставные репозитории (наследники с null-JdbcClient
 * и перекрытыми методами) и фейковый отправщик. Конфигурация — из application.yml.
 */
class PasswordResetServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String OLD_HASH = "$2a$12$oldhash";

    private FakeResets codes;
    private FakeUsers users;
    private FakeTokens tokens;
    private FakeMailer mailer;
    private SimpleMeterRegistry registry;
    private PasswordResetService service;

    @BeforeEach
    void setUp() throws Exception {
        AbvProperties props = loadProps();
        codes = new FakeResets();
        users = new FakeUsers(new UserRepository.Account(user(), OLD_HASH, true));
        tokens = new FakeTokens();
        mailer = new FakeMailer();
        registry = new SimpleMeterRegistry();
        service = new PasswordResetService(codes, users, tokens, mailer, props, new BusinessMetrics(registry));
    }

    @Test
    void issuesCodeAndSendsResetMail() {
        PasswordResetService.Requested requested = service.request(EMAIL, "127.0.0.1");

        assertThat(requested.email()).isEqualTo("u***@example.com");
        assertThat(requested.expiresIn()).isEqualTo(Duration.ofMinutes(15));
        assertThat(requested.resendIn()).isEqualTo(Duration.ofSeconds(60));
        assertThat(requested.devCode()).matches("\\d{6}");
        assertThat(mailer.calls).isEqualTo(1);
        assertThat(mailer.lastCode).isEqualTo(requested.devCode());
        // Письмо персонализировано: приветствие берёт никнейм аккаунта.
        assertThat(mailer.lastNickname).isEqualTo("Аня");
        // В «БД» лежит HMAC, а не сам код.
        assertThat(codes.hash).isNotEqualTo(requested.devCode()).hasSize(64);
    }

    @Test
    void unknownAddressGetsTheSameNeutralAnswerAndNoMail() {
        users.account = null;

        PasswordResetService.Requested requested = service.request("nobody@example.com", "127.0.0.1");

        assertThat(requested.devCode()).isNull();
        assertThat(mailer.calls).isZero();
    }

    @Test
    void resetsPasswordAndRevokesSessions() {
        PasswordResetService.Requested requested = service.request(EMAIL, "127.0.0.1");

        service.reset(EMAIL, requested.devCode(), "new-password-1");

        assertThat(users.updatedHash).isNotNull();
        assertThat(users.updatedHash).isNotEqualTo(OLD_HASH);
        assertThat(Passwords.matches(users.updatedHash, "new-password-1")).isTrue();
        assertThat(tokens.revokedFor).isEqualTo(USER_ID);
        assertThat(codes.consumed).isTrue();
    }

    @Test
    void countsWrongAttemptsAndBurnsCodeAfterLimit() {
        PasswordResetService.Requested requested = service.request(EMAIL, "127.0.0.1");
        String wrong = requested.devCode().equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 5; attempt++) {
            int expectedLeft = 5 - attempt;
            assertThatThrownBy(() -> service.reset(EMAIL, wrong, "new-password-1"))
                    .isInstanceOf(AbvException.class)
                    .satisfies(error -> {
                        AbvException e = (AbvException) error;
                        assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                        assertThat(e.details()).containsEntry("field", "code");
                        assertThat(e.details()).containsEntry("attempts_left", expectedLeft);
                    });
        }
        assertThat(codes.consumed).isTrue();
        assertThat(users.updatedHash).isNull();
        assertThat(tokens.revokedFor).isNull();
    }

    @Test
    void rejectsExpiredCode() {
        PasswordResetService.Requested requested = service.request(EMAIL, "127.0.0.1");
        codes.expire();

        assertThatThrownBy(() -> service.reset(EMAIL, requested.devCode(), "new-password-1"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("reason", "expired");
                });
    }

    @Test
    void validatesNewPasswordBeforeTouchingTheCode() {
        assertThatThrownBy(() -> service.reset(EMAIL, "123456", "short"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "password");
                });
    }

    @Test
    void blocksSecondRequestDuringCooldown() {
        service.request(EMAIL, "127.0.0.1");
        codes.backdate(Instant.now().minusSeconds(10));

        assertThatThrownBy(() -> service.request(EMAIL, "127.0.0.1"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.RATE_LIMITED);
                    assertThat(e.details()).containsKey("resend_in");
                });
        assertThat(mailer.calls).isEqualTo(1);
    }

    // ------------------------------------------------------------------ метрики

    @Test
    void countsRequestAndSuccessfulReset() {
        PasswordResetService.Requested requested = service.request(EMAIL, "127.0.0.1");

        service.reset(EMAIL, requested.devCode(), "new-password-1");

        assertThat(counter("ababawa.auth.otp.requests",
                "purpose", "password_reset", "result", "success", "reason", "none")).isEqualTo(1.0);
        assertThat(counter("ababawa.auth.otp.verifications",
                "purpose", "password_reset", "result", "success", "reason", "none")).isEqualTo(1.0);
    }

    @Test
    void labelsExpiredCodeAndRateLimitedRequest() {
        PasswordResetService.Requested requested = service.request(EMAIL, "127.0.0.1");
        codes.expire();

        assertThatThrownBy(() -> service.reset(EMAIL, requested.devCode(), "new-password-1"))
                .isInstanceOf(AbvException.class);
        assertThat(counter("ababawa.auth.otp.verifications",
                "purpose", "password_reset", "result", "failure", "reason", "expired")).isEqualTo(1.0);

        codes.backdate(Instant.now().minusSeconds(5));
        assertThatThrownBy(() -> service.request(EMAIL, "127.0.0.1"))
                .isInstanceOf(AbvException.class);
        assertThat(counter("ababawa.auth.otp.requests",
                "purpose", "password_reset", "result", "failure", "reason", "rate_limited")).isEqualTo(1.0);
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

    /** Подставной репозиторий кодов: хранит один активный код в памяти. */
    private static final class FakeResets extends PasswordResetRepository {

        private UUID id;
        private String hash;
        private int attempts;
        private Instant expiresAt;
        private Instant createdAt;
        private boolean consumed;

        FakeResets() {
            super(null);
        }

        @Override
        public Reset insert(UUID userId, String codeHash, Instant expiresAt, String ip) {
            this.id = UUID.randomUUID();
            this.hash = codeHash;
            this.attempts = 0;
            this.expiresAt = expiresAt;
            this.createdAt = Instant.now();
            this.consumed = false;
            return new Reset(id, userId, codeHash, attempts, expiresAt, createdAt);
        }

        @Override
        public Optional<Reset> active(UUID userId) {
            if (consumed || id == null || !expiresAt.isAfter(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new Reset(id, userId, hash, attempts, expiresAt, createdAt));
        }

        @Override
        public int registerAttempt(UUID resetId) {
            return ++attempts;
        }

        @Override
        public void consume(UUID resetId) {
            consumed = true;
        }

        @Override
        public void consumeAllForUser(UUID userId) {
            consumed = true;
        }

        @Override
        public Instant lastCreatedAt(UUID userId) {
            return createdAt;
        }

        @Override
        public int countSince(UUID userId, Instant since) {
            return createdAt != null && createdAt.isAfter(since) ? 1 : 0;
        }

        @Override
        public int purgeExpiredBefore(Instant cutoff) {
            return 0;
        }

        void expire() {
            this.expiresAt = Instant.now().minusSeconds(1);
        }

        void backdate(Instant moment) {
            this.createdAt = moment;
        }
    }

    /** Подставной UserRepository: один аккаунт в памяти. */
    private static final class FakeUsers extends UserRepository {

        private UserRepository.Account account;
        private String updatedHash;

        FakeUsers(UserRepository.Account account) {
            super(null);
            this.account = account;
        }

        @Override
        public Account accountByEmail(String login) {
            return account;
        }

        @Override
        public void updatePassword(UUID id, String passwordHash) {
            this.updatedHash = passwordHash;
        }
    }

    /** Подставной репозиторий refresh-токенов: запоминает отзыв сессий. */
    private static final class FakeTokens extends RefreshTokenRepository {

        private UUID revokedFor;

        FakeTokens() {
            super(null, null);
        }

        @Override
        public void revokeAllForUser(UUID userId) {
            this.revokedFor = userId;
        }
    }

    /** Фейковый отправщик: запоминает код вместо письма. */
    private static final class FakeMailer extends VerificationMailer {

        private int calls;
        private String lastNickname;
        private String lastCode;

        FakeMailer() {
            super(null, null, null, null);
        }

        @Override
        public boolean sendPasswordReset(String nickname, String email, String code) {
            calls++;
            lastNickname = nickname;
            lastCode = code;
            return true;
        }
    }
}
