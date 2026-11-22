package com.abavava.chat.mail;

import java.util.Locale;
import java.util.regex.Pattern;

/** Адреса в конверте SMTP: проверка формата и маскировка для логов. */
final class MailAddresses {

    private static final Pattern PATTERN = Pattern.compile("^[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+$");

    private MailAddresses() {
    }

    static boolean isValid(String address) {
        return address != null && address.length() <= 254 && PATTERN.matcher(address).matches();
    }

    /** `a***@example.com`: в логах не нужен полный адрес получателя. */
    static String mask(String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = address.substring(0, at);
        return (local.isEmpty() ? "*" : local.charAt(0) + "***") + address.substring(at).toLowerCase(Locale.ROOT);
    }
}
