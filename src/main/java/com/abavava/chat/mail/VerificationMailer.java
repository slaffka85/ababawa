package com.abavava.chat.mail;

import com.abavava.chat.config.AbvProperties;
import org.springframework.stereotype.Component;

/**
 * Письмо с кодом подтверждения: подставляет код, срок действия и адрес
 * поддержки в шаблоны из classpath.
 *
 * <p>Ссылок в письме нет намеренно: клик по письму не должен уводить на сайт,
 * а код получатель вводит в приложении. Один тап для копирования даёт нативная
 * пилюля «Copy code» в Gmail — письму для этого ничего не нужно.
 */
@Component
public class VerificationMailer {

    private final MailTemplates templates;
    private final SmtpMailSender sender;
    private final AbvProperties props;

    public VerificationMailer(MailTemplates templates, SmtpMailSender sender, AbvProperties props) {
        this.templates = templates;
        this.sender = sender;
        this.props = props;
    }

    /** @return true, если письмо принято к отправке (не «доставлено»). */
    public boolean sendCode(String email, String code) {
        return sender.send(compose(email, code));
    }

    /** Письмо со ссылкой на сброс пароля: тот же код, другой текст. */
    public boolean sendPasswordReset(String email, String code) {
        return sender.send(composePasswordReset(email, code));
    }

    /**
     * То же письмо, что уходит при регистрации, но без очереди: временный
     * {@code MailTestController} отправляет его синхронно и возвращает результат.
     *
     * <p>Письмо — без ссылок: единственное действие получателя — прочитать код и
     * ввести его в приложении. Копирование в один тап дают почтовые клиенты
     * (пилюля «Copy code» в Gmail, автозаполнение в Apple Mail), а не письмо:
     * HTML-письма не исполняют JS, поэтому собственная кнопка «копировать»
     * могла бы только переходить по ссылке.
     */
    public MailMessage compose(String email, String code) {
        AbvProperties.Mail mail = props.mail();
        int expiresMinutes = (int) Math.max(1L, props.emailVerification().codeTtl().toMinutes());

        MailTemplates.RenderedMail rendered = templates.verificationCode(
                code, expiresMinutes, mail.supportEmail(), email);
        return new MailMessage(email, rendered.subject(), rendered.text(), rendered.html());
    }

    /** Та же вёрстка, но текст про сброс пароля и своя тема. */
    public MailMessage composePasswordReset(String email, String code) {
        AbvProperties.Mail mail = props.mail();
        int expiresMinutes = (int) Math.max(1L, props.emailVerification().codeTtl().toMinutes());

        MailTemplates.RenderedMail rendered = templates.passwordReset(
                code, expiresMinutes, mail.supportEmail(), email);
        return new MailMessage(email, rendered.subject(), rendered.text(), rendered.html());
    }
}
