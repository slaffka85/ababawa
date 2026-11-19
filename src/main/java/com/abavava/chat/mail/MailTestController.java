package com.abavava.chat.mail;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import com.abavava.chat.security.EmailCodes;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * ВРЕМЕННЫЙ эндпоинт для настройки почты: генерирует код и отправляет письмо
 * на любой адрес синхронно, возвращая результат SMTP-сеанса.
 *
 * <pre>
 * POST /api/v1/mail/test  {"email": "you@example.com"}
 * 200 {"email": "you@example.com", "code": "483920", "sent": true}
 * 200 {"email": "you@example.com", "code": "483920", "sent": false, "error": "..."}
 * </pre>
 *
 * <p>Удалить после настройки SMTP: этот класс, строку {@code /api/v1/mail/} в
 * {@code AccessTokenFilter.PUBLIC_PREFIXES} и {@code /api/v1/mail/*} в
 * {@code WebFilterConfig}. Доступ повторяет режим ручной отправки кода
 * ({@code abv.email-verification.send-code}): {@code off} — 404, {@code dev} —
 * только вне production, {@code on} — всегда.
 */
@RestController
@RequestMapping("/api/v1/mail")
public class MailTestController {

    private static final Logger log = LoggerFactory.getLogger(MailTestController.class);

    private final AbvProperties props;
    private final VerificationMailer mailer;

    public MailTestController(AbvProperties props, VerificationMailer mailer) {
        this.props = props;
        this.mailer = mailer;
    }

    public record TestRequest(
            @JsonProperty("email") String email) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResponse(
            @JsonProperty("email") String email,
            @JsonProperty("code") String code,
            @JsonProperty("sent") boolean sent,
            @JsonProperty("error") String error) {
    }

    @PostMapping("/test")
    public TestResponse test(@RequestBody TestRequest body) {
        return send(body == null ? null : body.email());
    }

    /** Пакетный доступ нужен тестам: HTTP-обвязка здесь ничего не добавляет. */
    TestResponse send(String rawEmail) {
        ensureAvailable();

        String email = rawEmail == null ? "" : rawEmail.strip();
        if (email.isEmpty()) {
            throw AbvException.invalidField("email", "email is required");
        }
        if (!MailAddresses.isValid(email)) {
            throw AbvException.invalidField("email", "email must be a valid address");
        }

        String code = EmailCodes.generate(props.emailVerification().codeLength());
        AbvProperties.Mail mail = props.mail();
        if (!mail.enabled()) {
            return new TestResponse(email, code, false, "abv.mail.enabled=false");
        }

        MailMessage message = mailer.compose(email, code);
        byte[] raw = MimeWriter.build(mail.fromName(), mail.from(), email,
                message.subject(), message.text(), message.html());
        try {
            new SmtpTransport(new SmtpTransport.Settings(
                    mail.host(), mail.port(),
                    mail.username() == null ? "" : mail.username(),
                    mail.password() == null ? "" : mail.password(),
                    mail.starttls(), mail.ssl(), mail.timeout()))
                    .deliver(mail.from(), email, raw);
            log.info("test mail sent to {} via {}:{}", MailAddresses.mask(email), mail.host(), mail.port());
            return new TestResponse(email, code, true, null);
        } catch (Exception e) {
            log.warn("test mail to {} failed ({}:{}): {}",
                    MailAddresses.mask(email), mail.host(), mail.port(), e.toString());
            return new TestResponse(email, code, false, e.toString());
        }
    }

    private void ensureAvailable() {
        String mode = props.emailVerification().sendCode() == null
                ? "" : props.emailVerification().sendCode().trim().toLowerCase(Locale.ROOT);
        boolean available = "on".equals(mode) || ("dev".equals(mode) && !props.isProduction());
        if (!available) {
            throw AbvException.notFound("no such endpoint: /api/v1/mail/test");
        }
    }
}
