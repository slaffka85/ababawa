package com.abavava.chat.auth;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.domain.User;
import com.abavava.chat.mail.VerificationMailer;
import com.abavava.chat.security.EmailCodes;
import com.abavava.chat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Логика одноразовых кодов без БД и без SMTP: подставные репозитории
 * (наследники с null-JdbcClient, перекрытыми методами) и фейковый отправщик.
 * Конфигурация — настоящая, из application.yml.
 */
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private FakeCodes codes;
    private FakeUsers users;
    private FakeMailer mailer;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() throws Exception {
        AbvProperties props = loadProps();
        codes = new FakeCodes();
        users = new FakeUsers(new UserRepository.Account(user(), "$2a$12$hash", false));
        mailer = new FakeMailer();
        service = new EmailVerificationService(codes, users, mailer, props);
    }

    @Test
    void issuesCodeAndSendsItByMail() {
        EmailVerificationService.Issued issued = service.issue(user(), "127.0.0.1");

        assertThat(issued.code()).matches("\\d{6}");
        assertThat(issued.expiresIn()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(issued.resendIn()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(issued.emailQueued()).isTrue();
        assertThat(mailer.lastCode).isEqualTo(issued.code());
        assertThat(mailer.lastEmail).isEqualTo(EMAIL);
        // В письме приветствие по никнейму аккаунта.
        assertThat(mailer.lastNickname).isEqualTo("Аня");
        // В «БД» лежит HMAC, а не сам код.
        assertThat(codes.hash).isNotEqualTo(issued.code()).hasSize(64);
    }

    @Test
    void verifiesCorrectCodeAndMarksAddressConfirmed() {
        EmailVerificationService.Issued issued = service.issue(user(), "127.0.0.1");

        User verified = service.verify(EMAIL, issued.code());

        assertThat(verified.id()).isEqualTo(USER_ID);
        assertThat(users.markedVerified).isTrue();
        assertThat(codes.consumed).isTrue();
    }

    @Test
    void countsWrongAttemptsAndBurnsCodeAfterLimit() {
        EmailVerificationService.Issued issued = service.issue(user(), "127.0.0.1");
        String wrong = issued.code().equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 5; attempt++) {
            int expectedLeft = 5 - attempt;
            assertThatThrownBy(() -> service.verify(EMAIL, wrong))
                    .isInstanceOf(AbvException.class)
                    .satisfies(error -> {
                        AbvException e = (AbvException) error;
                        assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                        assertThat(e.details()).containsEntry("field", "code");
                        assertThat(e.details()).containsEntry("attempts_left", expectedLeft);
                    });
        }
        assertThat(codes.consumed).isTrue();
        // Код исчерпан: даже верное значение больше не принимается.
        assertThatThrownBy(() -> service.verify(EMAIL, issued.code()))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.VALIDATION_FAILED));
    }

    @Test
    void rejectsExpiredCode() {
        EmailVerificationService.Issued issued = service.issue(user(), "127.0.0.1");
        codes.expire();

        assertThatThrownBy(() -> service.verify(EMAIL, issued.code()))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("reason", "expired");
                });
    }

    @Test
    void blocksResendDuringCooldown() {
        service.issue(user(), "127.0.0.1");
        codes.backdate(Instant.now().minusSeconds(10));

        assertThatThrownBy(() -> service.resend(EMAIL, "127.0.0.1"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.RATE_LIMITED);
                    assertThat(e.details()).containsKey("resend_in");
                });
    }

    @Test
    void resendsAfterCooldown() {
        service.issue(user(), "127.0.0.1");
        codes.backdate(Instant.now().minusSeconds(120));

        EmailVerificationService.Resent resent = service.resend(EMAIL, "127.0.0.1");

        assertThat(resent.emailQueued()).isTrue();
        assertThat(resent.resendIn()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(mailer.calls).isEqualTo(2);
    }

    @Test
    void doesNotRevealWhetherUnknownAddressExists() {
        users.account = null;

        EmailVerificationService.Resent resent = service.resend("nobody@example.com", "127.0.0.1");

        assertThat(resent.emailQueued()).isFalse();
        assertThat(mailer.calls).isZero();
    }

    @Test
    void refusesToResendForConfirmedAddress() {
        users.account = new UserRepository.Account(user(), "$2a$12$hash", true);

        assertThatThrownBy(() -> service.resend(EMAIL, "127.0.0.1"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.CONFLICT));
    }

    @Test
    void manualSendRefusesUnknownAddress() {
        users.account = null;

        assertThatThrownBy(() -> service.sendCode("nobody@example.com", "127.0.0.1"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.CONFLICT);
                    assertThat(e.details()).containsEntry("reason", "email_not_registered");
                });
        assertThat(mailer.calls).isZero();
    }

    @Test
    void manualSendWorksForConfirmedAddress() {
        // Ручной инструмент проверки доставки: подтверждённый адрес тоже получает
        // письмо, хотя код из него не пройдёт verify (адрес уже подтверждён).
        users.account = new UserRepository.Account(user(), "$2a$12$hash", true);

        EmailVerificationService.Resent resent = service.sendCode(EMAIL, "127.0.0.1");

        assertThat(resent.emailQueued()).isTrue();
        assertThat(mailer.calls).isEqualTo(1);
        assertThat(mailer.lastCode).matches("\\d{6}");
    }

    @Test
    void manualSendIgnoresCooldownInDevelopment() {
        service.issue(user(), "127.0.0.1");
        // /auth/resend сейчас ответил бы 429: письмо ушло 5 секунд назад.
        codes.backdate(Instant.now().minusSeconds(5));

        EmailVerificationService.Resent resent = service.sendCode(EMAIL, "127.0.0.1");

        assertThat(resent.emailQueued()).isTrue();
        assertThat(resent.resendIn()).isEqualTo(java.time.Duration.ZERO);
        assertThat(resent.devCode()).matches("\\d{6}");
        assertThat(mailer.calls).isEqualTo(2);
        assertThat(mailer.lastCode).isEqualTo(resent.devCode());
    }

    @Test
    void manualSendReportsResultForKnownAddress() {
        EmailVerificationService.Resent resent = service.sendCode(EMAIL, "127.0.0.1");

        assertThat(resent.expiresIn()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(mailer.calls).isEqualTo(1);
        // Выпущенный код проверяется как обычный.
        assertThat(service.verify(EMAIL, resent.devCode()).id()).isEqualTo(USER_ID);
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
    private static final class FakeCodes extends EmailVerificationRepository {

        private UUID id;
        private String hash;
        private int attempts;
        private Instant expiresAt;
        private Instant createdAt;
        private boolean consumed;

        FakeCodes() {
            super(null);
        }

        @Override
        public Verification insert(UUID userId, String email, String codeHash, Instant expiresAt, String ip) {
            this.id = UUID.randomUUID();
            this.hash = codeHash;
            this.attempts = 0;
            this.expiresAt = expiresAt;
            this.createdAt = Instant.now();
            this.consumed = false;
            return new Verification(id, userId, email, codeHash, attempts, expiresAt, createdAt);
        }

        @Override
        public Optional<Verification> active(UUID userId) {
            if (consumed || id == null || !expiresAt.isAfter(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new Verification(id, userId, EMAIL, hash, attempts, expiresAt, createdAt));
        }

        @Override
        public int registerAttempt(UUID verificationId) {
            return ++attempts;
        }

        @Override
        public void consume(UUID verificationId) {
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
        private boolean markedVerified;

        FakeUsers(UserRepository.Account account) {
            super(null);
            this.account = account;
        }

        @Override
        public Account accountByEmail(String login) {
            return account;
        }

        @Override
        public User markEmailVerified(UUID id) {
            markedVerified = true;
            return account.user();
        }
    }

    /** Фейковый отправщик: запоминает код вместо письма. */
    private static final class FakeMailer extends VerificationMailer {

        private int calls;
        private String lastNickname;
        private String lastEmail;
        private String lastCode;

        FakeMailer() {
            super(null, null, null);
        }

        @Override
        public boolean sendCode(String nickname, String email, String code) {
            calls++;
            lastNickname = nickname;
            lastEmail = email;
            lastCode = code;
            return true;
        }
    }
}
