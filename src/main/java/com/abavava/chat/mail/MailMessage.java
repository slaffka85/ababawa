package com.abavava.chat.mail;

/** Готовое письмо: адресат, тема и обе части (text/plain + text/html). */
public record MailMessage(String to, String subject, String text, String html) {
}
