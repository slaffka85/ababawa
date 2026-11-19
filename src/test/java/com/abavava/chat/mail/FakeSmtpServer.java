package com.abavava.chat.mail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Минимальный SMTP-сервер для тестов транспорта и диагностики: один клиент,
 * без TLS. Отвечает на EHLO указанными возможностями, принимает MAIL/RCPT/DATA
 * (или отклоняет получателя) и записывает команды и тело письма.
 */
final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread thread;
    private final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    private final StringBuilder data = new StringBuilder();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final String capabilities;
    private final boolean rejectRecipient;
    private final String rejectRecipientReply;

    FakeSmtpServer(String capabilities, boolean rejectRecipient) throws IOException {
        this(capabilities, rejectRecipient, "550 5.1.1 no such user");
    }

    FakeSmtpServer(String capabilities, boolean rejectRecipient, String rejectRecipientReply) throws IOException {
        this.socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.capabilities = capabilities;
        this.rejectRecipient = rejectRecipient;
        this.rejectRecipientReply = rejectRecipientReply;
        this.thread = new Thread(this::serve, "fake-smtp");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    SmtpTransport.Settings settings(String username, String password) {
        return new SmtpTransport.Settings("127.0.0.1", port(), username, password,
                false, false, Duration.ofSeconds(5));
    }

    List<String> commands() {
        return new ArrayList<>(commands);
    }

    String data() {
        return data.toString();
    }

    void awaitCompletion() throws InterruptedException {
        if (!finished.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("fake smtp server did not finish in 5 seconds");
        }
        if (failure.get() != null) {
            throw new AssertionError("fake smtp server failed", failure.get());
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void serve() {
        try (Socket client = socket.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     client.getInputStream(), StandardCharsets.US_ASCII));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                     client.getOutputStream(), StandardCharsets.US_ASCII))) {
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
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    reply(out, "250-fake\r\n" + capabilities);
                } else if (command.startsWith("MAIL FROM")) {
                    reply(out, "250 2.1.0 ok");
                } else if (command.startsWith("RCPT TO")) {
                    reply(out, rejectRecipient ? rejectRecipientReply : "250 2.1.5 ok");
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

    private static void reply(BufferedWriter out, String text) throws IOException {
        out.write(text + "\r\n");
        out.flush();
    }

    static String decodePlainAuth(List<String> commands) {
        String auth = commands.stream()
                .filter(command -> command.startsWith("AUTH PLAIN "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AUTH PLAIN was not sent"));
        return new String(Base64.getDecoder().decode(auth.substring("AUTH PLAIN ".length())),
                StandardCharsets.UTF_8);
    }
}
