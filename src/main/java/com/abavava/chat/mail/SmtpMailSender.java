package com.abavava.chat.mail;

import com.abavava.chat.config.AbvProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Асинхронная отправка писем одним фоновым потоком.
 *
 * <p>Регистрация не должна ждать SMTP: письмо уходит в ограниченную очередь
 * (500 писем), а HTTP-ответ возвращается сразу. Недоступный SMTP пишется в лог
 * как ошибка — пользователь всегда может запросить код повторно.
 */
@Service
public class SmtpMailSender implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);
    private static final int QUEUE_CAPACITY = 500;

    private final AbvProperties.Mail config;
    private final ExecutorService worker;

    public SmtpMailSender(AbvProperties props) {
        this.config = props.mail();
        this.worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "abv-mail");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Ставит письмо в очередь.
     *
     * @return true, если письмо принято к отправке (не значит «доставлено»);
     *         false — почта выключена, адрес битый или очередь переполнена.
     */
    public boolean send(MailMessage message) {
        if (!config.enabled()) {
            log.info("abv.mail.enabled=false: mail to {} is not sent", MailAddresses.mask(message.to()));
            return false;
        }
        if (!MailAddresses.isValid(message.to())) {
            log.warn("refusing to send mail: malformed recipient address");
            return false;
        }
        byte[] raw;
        try {
            raw = MimeWriter.build(config.fromName(), config.from(), message.to(),
                    message.subject(), message.text(), message.html());
        } catch (RuntimeException e) {
            log.warn("cannot build mail message: {}", e.getMessage());
            return false;
        }
        try {
            worker.execute(() -> deliver(message.to(), raw));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("mail queue is full ({}), message to {} dropped", QUEUE_CAPACITY,
                    MailAddresses.mask(message.to()));
            return false;
        }
    }

    private void deliver(String to, byte[] raw) {
        SmtpTransport transport = new SmtpTransport(new SmtpTransport.Settings(
                config.host(),
                config.port(),
                config.username() == null ? "" : config.username(),
                config.password() == null ? "" : config.password(),
                config.starttls(),
                config.ssl(),
                config.timeout()));
        try {
            transport.deliver(config.from(), to, raw);
            log.info("mail accepted by {}:{} for {}", config.host(), config.port(), MailAddresses.mask(to));
        } catch (Exception e) {
            log.error("mail delivery failed ({}:{}): {}", config.host(), config.port(), e.toString());
        }
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }
}
