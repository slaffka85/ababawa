package com.abavava.chat.mail;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIME-письмо: заголовки, multipart/alternative и защита от инъекции заголовков. */
class MimeWriterTest {

    private static final String FROM = "no-reply@abavava.local";

    @Test
    void buildsMultipartAlternativeWithBothParts() {
        byte[] raw = MimeWriter.build("abavava", FROM, "user@example.com",
                "abavava verification code: 483920", "plain body line", "<p>html body</p>");
        String message = new String(raw, StandardCharsets.US_ASCII);

        assertThat(message).startsWith("From: abavava <" + FROM + ">\r\n");
        assertThat(message).contains("To: user@example.com\r\n");
        assertThat(message).contains("Subject: abavava verification code: 483920\r\n");
        assertThat(message).contains("Message-ID: <");
        assertThat(message).contains("@abavava.local>\r\n");
        assertThat(message).contains("MIME-Version: 1.0\r\n");
        assertThat(message).contains("Auto-Submitted: auto-generated\r\n");
        assertThat(message).contains("Content-Type: multipart/alternative; boundary=\"");

        List<String> parts = decodedParts(message);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).isEqualTo("plain body line");
        assertThat(parts.get(1)).isEqualTo("<p>html body</p>");
        assertThat(message).endsWith("\r\n");
    }

    @Test
    void stripsHeaderInjectionAttempts() {
        byte[] raw = MimeWriter.build("abavava", FROM, "user@example.com",
                "hello\r\nBcc: evil@example.com", "text", "html");
        String message = new String(raw, StandardCharsets.US_ASCII);

        assertThat(message).doesNotContain("\r\nBcc:");
        assertThat(message).contains("Subject: hello Bcc: evil@example.com\r\n");
    }

    @Test
    void encodesNonAsciiSubjectAsRfc2047() {
        byte[] raw = MimeWriter.build("abavava", FROM, "user@example.com", "код 123456", "text", "html");
        String message = new String(raw, StandardCharsets.US_ASCII);

        assertThat(message).contains("Subject: =?UTF-8?B?");
    }

    @Test
    void refusesInvalidAddresses() {
        assertThatThrownBy(() -> MimeWriter.build("abavava", FROM, "not-an-address", "s", "t", "h"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MimeWriter.build("abavava", "bad\r\naddress", "user@example.com", "s", "t", "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Достаёт base64-части письма и возвращает их в UTF-8. */
    private static List<String> decodedParts(String message) {
        Pattern part = Pattern.compile("Content-Transfer-Encoding: base64\r\n\r\n(.*?)\r\n--",
                Pattern.DOTALL);
        Matcher matcher = part.matcher(message);
        List<String> decoded = new ArrayList<>();
        while (matcher.find()) {
            decoded.add(new String(Base64.getMimeDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8));
        }
        return decoded;
    }
}
