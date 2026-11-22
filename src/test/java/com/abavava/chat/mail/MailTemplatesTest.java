package com.abavava.chat.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Шаблоны писем: код на месте, плейсхолдеров не осталось, HTML экранирован. */
class MailTemplatesTest {

    private final MailTemplates templates = new MailTemplates();

    @Test
    void rendersVerificationMailForHumansAndParsers() {
        MailTemplates.RenderedMail mail = verification("483920", "user@example.com");

        assertThat(mail.subject()).isEqualTo("[Ababawa] email verification code");
        assertThat(mail.text())
                .contains("Here is your Ababawa email verification code:")
                .contains("\n483920\n")
                .contains("valid for 15 minutes");
        assertThat(mail.html()).contains("Please verify your email address");
        // Видимый код — единый текстовый токен, не разбитый по ячейкам.
        assertThat(mail.html()).contains(">483920</span>");
        // Второе вхождение — служебная строка автозаполнения в футере, не ссылка.
        assertThat(countOccurrences(mail.html(), "483920")).isEqualTo(2);
        assertThat(mail.html()).contains("#483920");
        assertThat(mail.html()).doesNotContain("{{").doesNotContain("}}");
        assertThat(mail.text()).doesNotContain("{{").doesNotContain("}}");
    }

    @Test
    void rendersPasswordResetMail() {
        MailTemplates.RenderedMail mail = passwordReset("654321", "user@example.com");

        assertThat(mail.subject()).isEqualTo("[Ababawa] password reset code");
        assertThat(mail.text())
                .contains("request to reset the password")
                .contains("\n654321\n")
                .contains("valid for 15 minutes");
        assertThat(mail.html()).contains("Reset your password");
        assertThat(mail.html()).contains(">654321</span>");
        assertThat(mail.html()).doesNotContain("{{").doesNotContain("}}");
        assertThat(mail.text()).doesNotContain("{{").doesNotContain("}}");
    }

    @Test
    void lettersHaveNoLinksAtAll() {
        MailTemplates.RenderedMail verification = verification("483920", "user@example.com");
        MailTemplates.RenderedMail reset = passwordReset("654321", "user@example.com");

        // Клик по письму не должен никуда вести: ни кнопок, ни якорей, ни URL.
        for (MailTemplates.RenderedMail mail : new MailTemplates.RenderedMail[]{verification, reset}) {
            assertThat(mail.html()).doesNotContain("<a ").doesNotContain("href");
            assertThat(mail.text()).doesNotContain("http://").doesNotContain("https://");
        }
    }

    @Test
    void escapesHtmlButKeepsPlainTextReadable() {
        MailTemplates.RenderedMail mail = verification("123456", "a<b&c@example.com");

        assertThat(mail.html()).contains("a&lt;b&amp;c@example.com");
        assertThat(mail.html()).doesNotContain("a<b&c@example.com");
        assertThat(mail.text()).contains("a<b&c@example.com");
    }

    private MailTemplates.RenderedMail verification(String code, String recipient) {
        return templates.verificationCode(code, 15, "support@ababawa.tssv85.com", recipient);
    }

    private MailTemplates.RenderedMail passwordReset(String code, String recipient) {
        return templates.passwordReset(code, 15, "support@ababawa.tssv85.com", recipient);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
