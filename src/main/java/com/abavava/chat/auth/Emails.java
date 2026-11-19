package com.abavava.chat.auth;

import com.abavava.chat.domain.AbvException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Нормализация и маскировка email. Один формат для регистрации, входа и
 * подтверждения: регистр не важен, значения хранятся в lower-case, а наружу
 * (в ответах API и в интерфейсе) адрес отдаётся замаскированным.
 */
public final class Emails {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {
    }

    /** Валидирует адрес и приводит его к виду, в котором он лежит в БД. */
    public static String normalize(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw AbvException.invalidField("email", "email is required");
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw AbvException.invalidField("email", "email is too long");
        }
        if (!PATTERN.matcher(email).matches()) {
            throw AbvException.invalidField("email", "email is invalid");
        }
        String domain = email.substring(email.indexOf('@') + 1);
        if (!domain.contains(".")) {
            throw AbvException.invalidField("email", "email domain is invalid");
        }
        return email;
    }

    /** `a***@example.com`: достаточно, чтобы человек узнал свой адрес. */
    public static String mask(String email) {
        if (email == null || email.isEmpty()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email.length() <= 1 ? "***" : email.charAt(0) + "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    /** Технический адрес из конфигурации (From/Support): формат — без исключений. */
    public static boolean isValid(String email) {
        if (email == null) {
            return false;
        }
        String value = email.trim();
        return value.length() <= MAX_EMAIL_LENGTH
                && value.equals(value.toLowerCase(Locale.ROOT))
                && PATTERN.matcher(value).matches();
    }
}
