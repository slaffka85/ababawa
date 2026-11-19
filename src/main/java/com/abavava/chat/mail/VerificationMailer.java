package com.abavava.chat.mail;

import com.abavava.chat.config.AbvProperties;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.time.ZoneOffset;

/**
 * Письмо с кодом подтверждения: собирает ссылки и данные шаблона.
 *
 * <p>Ссылка «Copy code» несёт код во фрагменте (`#c=123456`): фрагмент не
 * уходит на сервер и не попадает в access-логи nginx, а страница
 * `/verify-email` кладёт код в буфер обмена.
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
        AbvProperties.Mail mail = props.mail();
        int expiresMinutes = (int) Math.max(1L, props.emailVerification().codeTtl().toMinutes());
        String appUrl = withoutTrailingSlash(mail.appUrl());
        String copyUrl = appUrl + "/verify-email#c=" + code;
        int year = Year.now(ZoneOffset.UTC).getValue();

        MailTemplates.VerificationMail rendered = templates.verificationCode(
                code, expiresMinutes, copyUrl, appUrl, mail.supportEmail(), email, year);
        return sender.send(new MailMessage(email, rendered.subject(), rendered.text(), rendered.html()));
    }

    private static String withoutTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
