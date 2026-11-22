package com.abavava.chat.mail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SMTP-клиент против локального фейкового сервера: сам протокол (EHLO,
 * MAIL/RCPT/DATA), dot-stuffing и AUTH PLAIN. Сети наружу не требуется.
 */
class SmtpTransportTest {

    @Test
    void deliversMessageOverPlainSmtpWithDotStuffing() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer("250-AUTH PLAIN LOGIN\r\n250 8BITMIME", false)) {
            SmtpTransport transport = new SmtpTransport(server.settings("", ""));
            byte[] body = "Subject: test\r\n\r\n.leading dot\r\nbody line\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

            transport.deliver("from@ababawa.local", "user@example.com", body);
            server.awaitCompletion();

            assertThat(server.commands()).anyMatch(command -> command.startsWith("EHLO "));
            assertThat(server.commands()).contains(
                    "MAIL FROM:<from@ababawa.local>",
                    "RCPT TO:<user@example.com>",
                    "DATA");
            // Строка, начинающаяся с точки, передаётся как «..» — иначе сервер
            // увидит конец DATA на первой же такой строке.
            assertThat(server.data()).contains("..leading dot\r\n");
            assertThat(server.data()).doesNotContain("\r\n.leading dot\r\n");
            assertThat(server.data()).endsWith("body line\r\n");
        }
    }

    @Test
    void authenticatesWithPlainWhenCredentialsAreConfigured() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer("250-AUTH PLAIN LOGIN\r\n250 8BITMIME", false)) {
            SmtpTransport transport = new SmtpTransport(server.settings("abv-user", "s3cret"));
            transport.deliver("from@ababawa.local", "user@example.com",
                    "Subject: test\r\n\r\nbody\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            server.awaitCompletion();

            String auth = server.commands().stream()
                    .filter(command -> command.startsWith("AUTH PLAIN "))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("AUTH PLAIN was not sent"));
            String decoded = new String(
                    java.util.Base64.getDecoder().decode(auth.substring("AUTH PLAIN ".length())),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(decoded).isEqualTo("\u0000abv-user\u0000s3cret");
        }
    }

    @Test
    void failsWhenRecipientIsRejected() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer("250-AUTH PLAIN LOGIN\r\n250 8BITMIME", true)) {
            SmtpTransport transport = new SmtpTransport(server.settings("", ""));

            assertThatThrownBy(() -> transport.deliver("from@ababawa.local", "user@example.com",
                    "Subject: test\r\n\r\nbody\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("550");
            server.awaitCompletion();
        }
    }

    /** Минимальный SMTP-сервер для тестов: один клиент, без TLS. */
    private static final class FakeSmtpServer implements AutoCloseable {

        private final java.net.ServerSocket socket;
        private final Thread thread;
        private final java.util.List<String> commands =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final StringBuilder data = new StringBuilder();
        private final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final String capabilities;
        private final boolean rejectRecipient;

        FakeSmtpServer(String capabilities, boolean rejectRecipient) throws IOException {
            this.socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            this.capabilities = capabilities;
            this.rejectRecipient = rejectRecipient;
            this.thread = new Thread(this::serve, "fake-smtp");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        SmtpTransport.Settings settings(String username, String password) {
            return new SmtpTransport.Settings("127.0.0.1", socket.getLocalPort(), username, password,
                    false, false, Duration.ofSeconds(5));
        }

        java.util.List<String> commands() {
            return new java.util.ArrayList<>(commands);
        }

        String data() {
            return data.toString();
        }

        void awaitCompletion() throws InterruptedException {
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if (failure.get() != null) {
                throw new AssertionError("fake smtp server failed", failure.get());
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        private void serve() {
            try (java.net.Socket client = socket.accept();
                 java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                         client.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                 java.io.BufferedWriter out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                         client.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII))) {
                reply(out, "220 fake ESMTP");
                String line;
                boolean inData = false;
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (line.equals(".")) {
                            inData = false;
                            reply(out, "250 2.0.0 queued");
                        } else {
                            data.append(line).append("\r\n");
                        }
                        continue;
                    }
                    commands.add(line);
                    String command = line.toUpperCase(java.util.Locale.ROOT);
                    if (command.startsWith("EHLO")) {
                        reply(out, "250-fake\r\n" + capabilities);
                    } else if (command.startsWith("MAIL FROM")) {
                        reply(out, "250 2.1.0 ok");
                    } else if (command.startsWith("RCPT TO")) {
                        reply(out, rejectRecipient ? "550 5.1.1 no such user" : "250 2.1.5 ok");
                    } else if (command.equals("DATA")) {
                        inData = true;
                        reply(out, "354 end with <CRLF>.<CRLF>");
                    } else if (command.startsWith("AUTH PLAIN")) {
                        reply(out, "235 2.7.0 accepted");
                    } else if (command.equals("QUIT")) {
                        reply(out, "221 bye");
                        break;
                    } else {
                        reply(out, "250 ok");
                    }
                }
            } catch (IOException e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        }

        private static void reply(java.io.BufferedWriter out, String text) throws IOException {
            out.write(text + "\r\n");
            out.flush();
        }
    }
}
