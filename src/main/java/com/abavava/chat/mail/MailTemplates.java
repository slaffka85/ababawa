package com.abavava.chat.mail;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Шаблоны писем из classpath:mail/ (HTML + текстовая часть).
 *
 * <p>Подстановка — простые плейсхолдеры `{{name}}` вместо шаблонизатора: писем
 * два (код подтверждения email и код сброса пароля), зависимость ради них в
 * проект не тянем. Значения для HTML экранируются отдельно от текстовой части,
 * поэтому один и тот же адрес не превращается в «&amp;amp;» в plain-text.
 */
@Component
public class MailTemplates {

    private static final String VERIFICATION_HTML = "mail/verification-code.html";
    private static final String VERIFICATION_TEXT = "mail/verification-code.txt";
    private static final String PASSWORD_RESET_HTML = "mail/password-reset.html";
    private static final String PASSWORD_RESET_TEXT = "mail/password-reset.txt";

    private final String verificationHtml;
    private final String verificationText;
    private final String passwordResetHtml;
    private final String passwordResetText;

    public MailTemplates() {
        this.verificationHtml = load(VERIFICATION_HTML);
        this.verificationText = load(VERIFICATION_TEXT);
        this.passwordResetHtml = load(PASSWORD_RESET_HTML);
        this.passwordResetText = load(PASSWORD_RESET_TEXT);
    }

    /** Готовое письмо с кодом: тема, текстовая часть и HTML. */
    public record RenderedMail(String subject, String text, String html) {
    }

    public RenderedMail verificationCode(String code, int expiresMinutes,
                                         String supportEmail, String recipientEmail) {
        // Тема по образцу писем GitHub: бренд в скобках и название действия.
        // Кода в теме нет — он виден и на локскрине; код живёт в теле письма.
        return render(verificationHtml, verificationText, "[Ababawa] email verification code",
                values(code, expiresMinutes, supportEmail, recipientEmail));
    }

    public RenderedMail passwordReset(String code, int expiresMinutes,
                                      String supportEmail, String recipientEmail) {
        return render(passwordResetHtml, passwordResetText, "[Ababawa] password reset code",
                values(code, expiresMinutes, supportEmail, recipientEmail));
    }

    private static Map<String, String> values(String code, int expiresMinutes,
                                              String supportEmail, String recipientEmail) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("code", code);
        values.put("expires_minutes", Integer.toString(expiresMinutes));
        values.put("support_email", supportEmail);
        values.put("recipient_email", recipientEmail);
        return values;
    }

    private static RenderedMail render(String htmlTemplate, String textTemplate, String subject,
                                       Map<String, String> values) {
        String text = render(textTemplate, values);
        String html = render(htmlTemplate, escape(values));
        return new RenderedMail(subject, text, html);
    }

    private static String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        if (rendered.contains("{{")) {
            throw new IllegalStateException("mail template has unresolved placeholders");
        }
        return rendered;
    }

    private static Map<String, String> escape(Map<String, String> values) {
        Map<String, String> escaped = new LinkedHashMap<>();
        values.forEach((key, value) -> escaped.put(key, escapeHtml(value)));
        return escaped;
    }

    private static String escapeHtml(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static String load(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load mail template " + path, e);
        }
    }
}
