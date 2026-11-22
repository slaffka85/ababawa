package com.abavava.chat.mail;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Сборка MIME-письма `multipart/alternative` (text + html).
 *
 * <p>Результат — байты в ASCII с CRLF: обе части кодируются base64, поэтому
 * переноситься по SMTP могут без 8BITMIME и без quoted-printable-сюрпризов.
 * Значения заголовков санитизируются: перевод строки в теме — классический
 * способ подмешать свои заголовки.
 */
final class MimeWriter {

    private static final String CRLF = "\r\n";
    private static final Base64.Encoder BASE64 = Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII));
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);
    private static final int MAX_HEADER_LENGTH = 998;

    private MimeWriter() {
    }

    static byte[] build(String fromName, String from, String to, String subject, String text, String html) {
        String safeFrom = address(from, "from");
        String safeTo = address(to, "to");
        String boundary = "=_ababawa_" + UUID.randomUUID().toString().replace("-", "");
        String domain = domainOf(safeFrom);

        StringBuilder message = new StringBuilder(1024 + text.length() + html.length());
        message.append("From: ").append(displayName(fromName, safeFrom)).append(CRLF);
        message.append("To: ").append(safeTo).append(CRLF);
        message.append("Subject: ").append(encodedWord(headerValue(subject))).append(CRLF);
        message.append("Date: ").append(DATE.format(ZonedDateTime.now())).append(CRLF);
        message.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(domain).append('>').append(CRLF);
        message.append("MIME-Version: 1.0").append(CRLF);
        // Транзакционное письмо: автоответы и «отпускные» на него не нужны.
        message.append("Auto-Submitted: auto-generated").append(CRLF);
        message.append("X-Auto-Response-Suppress: All").append(CRLF);
        message.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append('"').append(CRLF);
        message.append(CRLF);
        message.append(part(boundary, "text/plain", text));
        message.append(part(boundary, "text/html", html));
        message.append("--").append(boundary).append("--").append(CRLF);

        return message.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String part(String boundary, String type, String content) {
        byte[] encoded = BASE64.encode((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        return "--" + boundary + CRLF
                + "Content-Type: " + type + "; charset=UTF-8" + CRLF
                + "Content-Transfer-Encoding: base64" + CRLF
                + CRLF
                + new String(encoded, StandardCharsets.US_ASCII) + CRLF;
    }

    /** Адрес в угловых скобках не терпит пробелов, CR/LF и скобок. */
    private static String address(String raw, String what) {
        String value = raw == null ? "" : raw.trim();
        if (!MailAddresses.isValid(value)) {
            throw new IllegalArgumentException("invalid " + what + " address for MIME message");
        }
        return value;
    }

    private static String displayName(String rawName, String address) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return address;
        }
        return encodedWord(headerValue(name)) + " <" + address + ">";
    }

    /** Заголовок без переводов строк и служебных символов. */
    private static String headerValue(String raw) {
        String value = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (value.length() > MAX_HEADER_LENGTH) {
            value = value.substring(0, MAX_HEADER_LENGTH);
        }
        return value;
    }

    /** Не-ASCII значения кодируются по RFC 2047. */
    private static String encodedWord(String value) {
        if (value.chars().allMatch(ch -> ch < 128)) {
            return value;
        }
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + encoded + "?=";
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        String domain = at >= 0 ? address.substring(at + 1) : "";
        return domain.isEmpty() ? "ababawa.local" : domain;
    }
}
