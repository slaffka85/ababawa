package com.abavava.chat.http;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * IP клиента с учётом доверенных reverse proxy.
 *
 * <p>Проблема, которую решает класс: заголовки {@code X-Forwarded-For},
 * {@code X-Real-IP} и {@code Forwarded} пишет кто угодно. Nginx на хосте
 * собирает {@code X-Forwarded-For} через {@code $proxy_add_x_forwarded_for} —
 * то есть <b>дописывает</b> реальный адрес справа к тому, что прислал клиент.
 * Если брать первый (левый) адрес, любой клиент подставит себе любой IP и обойдёт
 * rate limit и защиту от перебора.
 *
 * <p>Правила разрешения:
 * <ol>
 *   <li>заголовкам верим, только если непосредственный пир
 *       ({@code request.getRemoteAddr()}) входит в доверенные сети
 *       {@code abv.trusted-proxies}; иначе возвращается сам пир, а заголовки
 *       игнорируются целиком;</li>
 *   <li>цепочка адресов из {@code X-Forwarded-For} читается <b>справа
 *       налево</b>: первый недоверенный хоп — это адрес клиента, который видел
 *       ближайший доверенный прокси; всё левее мог дописать сам клиент и
 *       отбрасывается;</li>
 *   <li>{@code X-Real-IP} и {@code Forwarded} используются только как запасной
 *       вариант, когда в {@code X-Forwarded-For} нет ни одного корректного
 *       адреса (оба заголовка тоже требуют доверенного пира);</li>
 *   <li>учитываются только каноничные IP-литералы (IPv4 из четырёх октетов без
 *       ведущих нулей, IPv6 — как его разбирает
 *       {@link InetAddress#ofLiteral(String)}); {@code unknown}, зоны
 *       ({@code %eth0}), имена хостов и прочий мусор отбрасываются — DNS при
 *       разборе не выполняется;</li>
 *   <li>IPv4 и IPv6 поддерживаются одинаково, IPv4-mapped IPv6
 *       ({@code ::ffff:203.0.113.7}) приводится к IPv4, чтобы доверенные сети и
 *       сравнения работали одинаково.</li>
 * </ol>
 *
 * <p>Результат — каноничное текстовое представление адреса (IPv4 — четыре
 * октета, IPv6 — полная форма без сжатия). Оно пригодно и для метки в БД
 * ({@code refresh_tokens.ip}, {@code email_verifications.ip}), и как ключ
 * rate limit'а.
 *
 * <p>Класс не бросает исключений на «плохих» входных данных: непонятный
 * заголовок просто не используется. Ошибка в конфигурации
 * {@code abv.trusted-proxies} обнаруживается при старте
 * ({@link IllegalArgumentException} из {@link #parse(String)}).
 */
public final class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";
    private static final String FORWARDED = "Forwarded";

    private final List<Network> trusted;

    private ClientIpResolver(List<Network> trusted) {
        this.trusted = trusted;
    }

    /**
     * Разбирает список доверенных сетей: {@code 10.0.0.0/8}, {@code 127.0.0.1},
     * {@code ::1/128} — через запятую. Пустая строка допустима (никому не
     * доверяем, заголовки всегда игнорируются).
     *
     * @throws IllegalArgumentException адрес или маска не разобраны
     */
    public static ClientIpResolver parse(String trustedProxyCsv) {
        List<Network> networks = new ArrayList<>();
        if (trustedProxyCsv != null) {
            for (String raw : trustedProxyCsv.split(",")) {
                String entry = raw.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                networks.add(Network.parse(entry));
            }
        }
        return new ClientIpResolver(List.copyOf(networks));
    }

    /** IP клиента для запроса: доверенный ли пир, цепочка хопов и т.д. */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        return resolve(request.getRemoteAddr(),
                request.getHeader(X_FORWARDED_FOR),
                request.getHeader(X_REAL_IP),
                request.getHeader(FORWARDED));
    }

    /** То же без сервлет-зависимостей: удобно проверять тестами. */
    String resolve(String remoteAddr, String forwardedFor, String xRealIp, String forwarded) {
        String peerText = hostPart(remoteAddr);
        Address peer = parseAddress(peerText);
        if (peer == null) {
            // Пир не IP-литерал (не должно случаться с Tomcat): доверять заголовкам
            // нечему, возвращаем как есть.
            return peerText;
        }
        if (!isTrusted(peer.bytes)) {
            // Прямое соединение не от прокси: любой X-Forwarded-* подделал клиент.
            return peer.render();
        }

        List<Address> chain = chain(forwardedFor, xRealIp, forwarded);
        Address client = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Address hop = chain.get(i);
            if (!isTrusted(hop.bytes)) {
                // Ближайший к нам недоверенный хоп и есть клиент.
                return hop.render();
            }
            client = hop;
        }
        // Вся цепочка доверенная (или цепочки нет): отдаём левый крайний адрес.
        return client != null ? client.render() : peer.render();
    }

    private List<Address> chain(String forwardedFor, String xRealIp, String forwarded) {
        List<Address> hops = new ArrayList<>();
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String part : forwardedFor.split(",")) {
                Address hop = parseAddress(part);
                if (hop != null) {
                    hops.add(hop);
                }
            }
        }
        if (!hops.isEmpty()) {
            return hops;
        }
        Address real = parseAddress(xRealIp);
        if (real != null) {
            return List.of(real);
        }
        return forwardedChain(forwarded);
    }

    /** Разбор RFC 7239 {@code Forwarded: for=203.0.113.7;proto=https, for=...}. */
    private static List<Address> forwardedChain(String header) {
        List<Address> hops = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return hops;
        }
        for (String element : header.split(",")) {
            for (String pair : element.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0 || !"for".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                    continue;
                }
                String value = pair.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1).trim();
                }
                // "unknown" и обфусцированные идентификаторы (_hidden) — не адреса.
                if (value.isEmpty() || "unknown".equalsIgnoreCase(value) || value.startsWith("_")) {
                    continue;
                }
                Address hop = parseAddress(value);
                if (hop != null) {
                    hops.add(hop);
                }
            }
        }
        return hops;
    }

    private boolean isTrusted(byte[] address) {
        for (Network network : trusted) {
            if (network.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** {@code host:port} → {@code host}; IPv6-литералы в скобках сохраняются. */
    static String hostPart(String hostport) {
        if (hostport == null) {
            return "";
        }
        String value = hostport.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close < 0 ? value : value.substring(0, close + 1);
        }
        int colon = value.indexOf(':');
        if (colon > 0 && colon == value.lastIndexOf(':')) {
            // Ровно одно двоеточие: IPv4:port. У IPv6 их минимум два.
            String rest = value.substring(colon + 1);
            if (!rest.isEmpty() && rest.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                return value.substring(0, colon);
            }
        }
        return value;
    }

    /**
     * Один адрес из текста: {@code 1.2.3.4}, {@code [2001:db8::1]},
     * {@code 1.2.3.4:80}, {@code [2001:db8::1]:443}. {@code null} — не литерал.
     */
    static Address parseAddress(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            String rest = value.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                return null;
            }
            return literal(value.substring(1, close));
        }
        Address direct = literal(value);
        if (direct != null) {
            return direct;
        }
        // IPv4:port (формат Forwarded и X-Forwarded-For от некоторых прокси).
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon == value.indexOf(':') && value.indexOf('.') > 0) {
            return literal(value.substring(0, colon));
        }
        return null;
    }

    private static Address literal(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.indexOf(':') >= 0) {
            try {
                byte[] bytes = InetAddress.ofLiteral(value).getAddress();
                if (bytes.length == 4) {
                    return new Address(bytes);
                }
                if (bytes.length != 16) {
                    return null;
                }
                byte[] mapped = ipv4Mapped(bytes);
                return mapped == null ? new Address(bytes) : new Address(mapped);
            } catch (IllegalArgumentException e) {
                // Не литерал: имя хоста, зона (%eth0), мусор. DNS не вызывается.
                return null;
            }
        }
        byte[] ipv4 = ipv4(value);
        return ipv4 == null ? null : new Address(ipv4);
    }

    /** Строгий IPv4: четыре октета 0..255, без ведущих нулей и сокращений. */
    private static byte[] ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int c = 0; c < part.length(); c++) {
                char ch = part.charAt(c);
                if (ch < '0' || ch > '9') {
                    return null;
                }
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return null;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    /** {@code ::ffff:203.0.113.7} → 4 байта; иначе {@code null}. */
    private static byte[] ipv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return null;
            }
        }
        if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return null;
        }
        return Arrays.copyOfRange(bytes, 12, 16);
    }

    /** Каноничное представление адреса; пустая строка только на невозможном сбое. */
    private static String render(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return "";
        }
    }

    /** Разобранный адрес: байты (4 или 16) и способ его вывести. */
    static final class Address {

        private final byte[] bytes;

        private Address(byte[] bytes) {
            this.bytes = bytes;
        }

        String render() {
            return ClientIpResolver.render(bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Address address && Arrays.equals(bytes, address.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Доверенная сеть: префикс и длина маски. */
    private static final class Network {

        private final byte[] prefix;
        private final int bits;

        private Network(byte[] prefix, int bits) {
            this.prefix = prefix;
            this.bits = bits;
        }

        static Network parse(String entry) {
            int slash = entry.indexOf('/');
            String addressPart = slash >= 0 ? entry.substring(0, slash) : entry;
            Address address = ClientIpResolver.parseAddress(addressPart);
            if (address == null) {
                throw new IllegalArgumentException(
                        "not an IP address or CIDR: " + entry);
            }
            int maxBits = address.bytes.length * 8;
            int bits = maxBits;
            if (slash >= 0) {
                String mask = entry.substring(slash + 1).trim();
                try {
                    bits = Integer.parseInt(mask);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid prefix length in " + entry);
                }
                if (bits < 0 || bits > maxBits) {
                    throw new IllegalArgumentException(
                            "prefix length out of range in " + entry + " (0.." + maxBits + ")");
                }
            }
            return new Network(address.bytes, bits);
        }

        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                return false;
            }
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            int remainder = bits % 8;
            if (remainder != 0) {
                int mask = 0xFF << (8 - remainder);
                return (address[fullBytes] & mask) == (prefix[fullBytes] & mask);
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Network network
                    && bits == network.bits
                    && Arrays.equals(prefix, network.prefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(prefix), bits);
        }

        @Override
        public String toString() {
            return render(prefix) + "/" + bits;
        }
    }
}
