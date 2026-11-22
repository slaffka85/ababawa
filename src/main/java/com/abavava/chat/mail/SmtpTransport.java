package com.abavava.chat.mail;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Минимальный SMTP-клиент (RFC 5321): EHLO, опционально STARTTLS и
 * AUTH PLAIN/LOGIN, затем MAIL/RCPT/DATA.
 *
 * <p>Внешних зависимостей нет — как и у остальных интеграций проекта
 * (см. {@code FcmPushSender} на java.net.http). Этого набора команд достаточно
 * и для Postfix на том же хосте (порт 25 без авторизации), и для внешнего
 * релея с TLS-авторизацией (587/465). Возможностей, которые здесь не нужны
 * (DSN, PIPELINING, 8BITMIME), клиент не заявляет.
 */
final class SmtpTransport {

    private static final String CRLF = "\r\n";

    /** Параметры подключения; пароль может быть пустым (локальный релей). */
    record Settings(String host, int port, String username, String password, boolean starttls, boolean ssl,
                    Duration timeout) {
    }

    private final Settings settings;

    SmtpTransport(Settings settings) {
        this.settings = settings;
    }

    void deliver(String envelopeFrom, String recipient, byte[] message) throws IOException {
        int timeout = (int) Math.max(1_000L, Math.min(120_000L, settings.timeout().toMillis()));
        try (Channel channel = new Channel(timeout)) {
            channel.open(settings.host(), settings.port(), settings.ssl());
            channel.expect(220);
            String capabilities = channel.ehlo();
            if (settings.starttls()) {
                channel.write("STARTTLS" + CRLF);
                channel.expect(220);
                channel.startTls(settings.host(), settings.port());
                capabilities = channel.ehlo();
            }
            if (!isBlank(settings.username())) {
                authenticate(channel, capabilities);
            }
            channel.write("MAIL FROM:<" + envelopeFrom + ">" + CRLF);
            channel.expect(250);
            channel.write("RCPT TO:<" + recipient + ">" + CRLF);
            channel.expect(250, 251);
            channel.write("DATA" + CRLF);
            channel.expect(354);
            channel.writeData(message);
            channel.expect(250);
            channel.write("QUIT" + CRLF);
            try {
                channel.expect(221);
            } catch (IOException ignored) {
                // Сервер вправе закрыть соединение сразу после QUIT — письмо уже принято.
            }
        }
    }

    private void authenticate(Channel channel, String capabilities) throws IOException {
        String advertised = capabilities.toUpperCase(Locale.ROOT);
        String user = settings.username();
        String password = settings.password() == null ? "" : settings.password();
        boolean authAdvertised = advertised.contains("AUTH");
        if (authAdvertised && (advertised.contains("PLAIN") || !advertised.contains("LOGIN"))) {
            String token = base64("\u0000" + user + "\u0000" + password);
            channel.write("AUTH PLAIN " + token + CRLF);
            channel.expect(235);
            return;
        }
        if (authAdvertised && advertised.contains("LOGIN")) {
            channel.write("AUTH LOGIN" + CRLF);
            channel.expect(334);
            channel.write(base64(user) + CRLF);
            channel.expect(334);
            channel.write(base64(password) + CRLF);
            channel.expect(235);
            return;
        }
        throw new IOException("SMTP server does not advertise AUTH, but credentials are configured");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Одно SMTP-соединение с таймаутами и (опционально) TLS. */
    private static final class Channel implements Closeable {

        private final int timeout;
        private Socket socket;
        private BufferedReader in;
        private BufferedWriter out;

        Channel(int timeout) {
            this.timeout = timeout;
        }

        void open(String host, int port, boolean implicitTls) throws IOException {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), timeout);
            raw.setSoTimeout(timeout);
            if (implicitTls) {
                SSLSocket tls = (SSLSocket) tlsFactory().createSocket(raw, host, port, true);
                tls.setUseClientMode(true);
                tls.setSSLParameters(verifying(tls.getSSLParameters()));
                tls.startHandshake();
                attach(tls);
            } else {
                attach(raw);
            }
        }

        void startTls(String host, int port) throws IOException {
            SSLSocket tls = (SSLSocket) tlsFactory().createSocket(socket, host, port, true);
            tls.setUseClientMode(true);
            tls.setSSLParameters(verifying(tls.getSSLParameters()));
            tls.startHandshake();
            attach(tls);
        }

        private void attach(Socket connection) throws IOException {
            this.socket = connection;
            this.in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            this.out = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.US_ASCII));
        }

        String ehlo() throws IOException {
            write("EHLO " + localName() + CRLF);
            return expect(250);
        }

        void write(String command) throws IOException {
            out.write(command);
            out.flush();
        }

        /** Тело письма: точки в начале строк удваиваются, в конце — «.» на своей строке. */
        void writeData(byte[] message) throws IOException {
            String data = new String(message, StandardCharsets.US_ASCII);
            StringBuilder stuffed = new StringBuilder(data.length() + 16);
            boolean atLineStart = true;
            for (int i = 0; i < data.length(); i++) {
                char ch = data.charAt(i);
                if (atLineStart && ch == '.') {
                    stuffed.append('.');
                }
                stuffed.append(ch);
                atLineStart = ch == '\n';
            }
            out.write(stuffed.toString());
            if (stuffed.length() == 0 || stuffed.charAt(stuffed.length() - 1) != '\n') {
                out.write(CRLF);
            }
            out.write("." + CRLF);
            out.flush();
        }

        String expect(int... expected) throws IOException {
            String response = read();
            int code = parseCode(response);
            for (int candidate : expected) {
                if (candidate == code) {
                    return response;
                }
            }
            throw new IOException("SMTP " + Arrays.toString(expected) + " expected, got: " + oneLine(response));
        }

        private String read() throws IOException {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("SMTP connection closed by server");
            }
            StringBuilder response = new StringBuilder(line);
            if (line.length() >= 4 && line.charAt(3) == '-') {
                String code = line.substring(0, 3);
                while (true) {
                    line = in.readLine();
                    if (line == null) {
                        throw new IOException("SMTP connection closed by server");
                    }
                    response.append('\n').append(line);
                    if (line.length() == 3 && line.equals(code)) {
                        break;
                    }
                    if (line.length() >= 4 && line.startsWith(code) && line.charAt(3) == ' ') {
                        break;
                    }
                }
            }
            return response.toString();
        }

        private static int parseCode(String response) throws IOException {
            if (response.length() < 3) {
                throw new IOException("malformed SMTP response: " + oneLine(response));
            }
            try {
                return Integer.parseInt(response.substring(0, 3));
            } catch (NumberFormatException e) {
                throw new IOException("malformed SMTP response: " + oneLine(response), e);
            }
        }

        private static String oneLine(String response) {
            int end = response.indexOf('\n');
            return end < 0 ? response : response.substring(0, end);
        }

        private static SSLSocketFactory tlsFactory() {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        /** Проверка имени хоста в сертификате: без неё TLS защищает «в никуда». */
        private static SSLParameters verifying(SSLParameters parameters) {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return parameters;
        }

        private static String localName() {
            try {
                String name = InetAddress.getLocalHost().getHostName();
                return name == null || name.isBlank() ? "ababawa.local" : name;
            } catch (Exception e) {
                return "ababawa.local";
            }
        }

        @Override
        public void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // соединение уже разорвано — закрывать больше нечего
                }
            }
        }
    }
}
