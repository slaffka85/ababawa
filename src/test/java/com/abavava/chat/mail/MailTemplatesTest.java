package com.abavava.chat.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Шаблон письма: код на месте, плейсхолдеров не осталось, HTML экранирован. */
class MailTemplatesTest {

    private final MailTemplates templates = new MailTemplates();

    @Test
    void rendersVerificationMailForHumansAndParsers() {
        MailTemplates.VerificationMail mail = render("483920", "user@example.com");

        assertThat(mail.subject()).isEqualTo("abavava verification code: 483920");
        assertThat(mail.text())
                .contains("Your verification code is:")
                .contains("\n483920\n");
        assertThat(mail.html()).contains("Your verification code is:");
        // Код есть и в блоке кода, и в ссылке «Copy code» — ровно два вхождения.
        assertThat(countOccurrences(mail.html(), "483920")).isEqualTo(2);
        // Единый текстовый токен, а не разбивка по ячейкам.
        assertThat(mail.html()).containsPattern("<span class=\"code\"[^>]*>483920</span>");
        assertThat(mail.html()).doesNotContain("{{").doesNotContain("}}");
        assertThat(mail.text()).doesNotContain("{{").doesNotContain("}}");
        assertThat(mail.html()).contains("abavava verification code");
    }

    @Test
    void escapesHtmlButKeepsPlainTextReadable() {
        MailTemplates.VerificationMail mail = render("123456", "a<b&c@example.com");

        assertThat(mail.html()).contains("a&lt;b&amp;c@example.com");
        assertThat(mail.html()).doesNotContain("a<b&c@example.com");
        assertThat(mail.text()).contains("a<b&c@example.com");
    }

    private MailTemplates.VerificationMail render(String code, String recipient) {
        return templates.verificationCode(
                code,
                15,
                "https://abavava.tssv85.com/verify-email#c=" + code,
                "https://abavava.tssv85.com",
                "support@abavava.tssv85.com",
                recipient,
                2026);
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
