package com.abavava.chat.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Коды подтверждения: генерация, HMAC-хеш и привязка к пользователю. */
class EmailCodesTest {

    private static final String PEPPER = "test-secret-with-enough-length-32";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void generatesRequestedNumberOfDigits() {
        for (int i = 0; i < 200; i++) {
            String code = EmailCodes.generate(6);
            assertThat(code).hasSize(6).matches("\\d{6}");
        }
    }

    @Test
    void hashIsDeterministicButBoundToUserAndSecret() {
        String code = "123456";
        String hash = EmailCodes.hash(PEPPER, USER, code);

        assertThat(EmailCodes.hash(PEPPER, USER, code)).isEqualTo(hash);
        assertThat(EmailCodes.hash(PEPPER, OTHER_USER, code)).isNotEqualTo(hash);
        assertThat(EmailCodes.hash("another-secret", USER, code)).isNotEqualTo(hash);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void matchesAcceptsOnlyTheRightCode() {
        String code = "483920";
        String hash = EmailCodes.hash(PEPPER, USER, code);

        assertThat(EmailCodes.matches(PEPPER, USER, code, hash)).isTrue();
        assertThat(EmailCodes.matches(PEPPER, USER, "483921", hash)).isFalse();
        assertThat(EmailCodes.matches(PEPPER, OTHER_USER, code, hash)).isFalse();
        assertThat(EmailCodes.matches(PEPPER, USER, null, hash)).isFalse();
        assertThat(EmailCodes.matches(PEPPER, USER, code, null)).isFalse();
    }

    @Test
    void wellFormedRequiresExactDigits() {
        assertThat(EmailCodes.isWellFormed("123456", 6)).isTrue();
        assertThat(EmailCodes.isWellFormed("000000", 6)).isTrue();
        assertThat(EmailCodes.isWellFormed("12345", 6)).isFalse();
        assertThat(EmailCodes.isWellFormed("1234567", 6)).isFalse();
        assertThat(EmailCodes.isWellFormed("12345a", 6)).isFalse();
        assertThat(EmailCodes.isWellFormed(" 123456", 6)).isFalse();
        assertThat(EmailCodes.isWellFormed(null, 6)).isFalse();
    }

    @Test
    void digitsOnlyCleansPastedText() {
        assertThat(EmailCodes.digitsOnly(" 483 920 ")).isEqualTo("483920");
        assertThat(EmailCodes.digitsOnly("code: 483920")).isEqualTo("483920");
        assertThat(EmailCodes.digitsOnly(null)).isEmpty();
        assertThat(EmailCodes.digitsOnly("abc")).isEmpty();
    }
}
