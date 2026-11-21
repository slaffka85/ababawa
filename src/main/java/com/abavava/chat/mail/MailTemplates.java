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
 * <p>Подстановка — простые плейсхолдеры `{{name}}` вместо шаблонизатора:
 * письмо одно, зависимость ради него в проект не тянем. Значения для HTML
 * экранируются отдельно от текстовой части, поэтому один и тот же адрес
 * не превращается в «&amp;amp;» в plain-text.
 */
@Component
public class MailTemplates {

    private static final String HTML_TEMPLATE = "mail/verification-code.html";
    private static final String TEXT_TEMPLATE = "mail/verification-code.txt";

    private final String htmlTemplate;
    private final String textTemplate;

    public MailTemplates() {
        this.htmlTemplate = load(HTML_TEMPLATE);
        this.textTemplate = load(TEXT_TEMPLATE);
    }

    /** Готовое письмо с кодом подтверждения. */
    public record VerificationMail(String subject, String text, String html) {
    }

    public VerificationMail verificationCode(String code, int expiresMinutes,
                                             String supportEmail, String recipientEmail) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("code", code);
        values.put("expires_minutes", Integer.toString(expiresMinutes));
        values.put("support_email", supportEmail);
        values.put("recipient_email", recipientEmail);

        String text = render(textTemplate, values);
        String html = render(htmlTemplate, escape(values));
        // Тема по образцу писем GitHub: бренд в скобках и название действия.
        // Кода в теме нет — он виден и на локскрине; код живёт в теле письма.
        return new VerificationMail("[Ababawa] email verification code", text, html);
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
