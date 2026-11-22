package com.abavava.chat.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Одноразовые коды подтверждения email.
 *
 * <p>Код — короткое числовое значение (по умолчанию 6 цифр), поэтому в БД
 * нельзя хранить ни сам код, ни его «голый» хеш: 10^6 вариантов подбираются
 * офлайн мгновенно. Используется HMAC-SHA256 с серверным секретом
 * (ABV_JWT_SECRET) над строкой «&lt;user_id&gt;:&lt;code&gt;»: без секрета из
 * дампа БД код не восстановить, а привязка к пользователю не даёт
 * переиспользовать чужой код.
 */
public final class EmailCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private EmailCodes() {
    }

    /** Криптослучайный код из `length` десятичных цифр (ведущие нули допустимы). */
    public static String generate(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return code.toString();
    }

    /** HMAC-SHA256 в hex от «user_id:code». */
    public static String hash(String pepper, UUID userId, String code) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
            byte[] digest = mac.doFinal((userId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("hmac-sha256 is not available", e);
        }
    }

    /** Сравнение за постоянное время: утечка результата по времени не нужна. */
    public static boolean matches(String pepper, UUID userId, String code, String expectedHash) {
        if (code == null || expectedHash == null) {
            return false;
        }
        String actual = hash(pepper, userId, code);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** Ровно `length` цифр, без пробелов и разделителей — иначе код не наш. */
    public static boolean isWellFormed(String raw, int length) {
        if (raw == null || raw.length() != length) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /** Убирает из пользовательского ввода всё, кроме цифр (вставка «483 920»). */
    public static String digitsOnly(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        return digits.toString();
    }
}
