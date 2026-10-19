package com.example.learnhub.notification.infrastructure;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Wraps {@link JavaMailSender} to send a plain-text message to Mailpit. Logs only the notification id
 * and whether it succeeded — never the recipient address or the message body (FR-021, the brief's
 * explicit instruction).
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private static final String FROM_ADDRESS = "learnhub@example.com";

    private final JavaMailSender mailSender;

    public EmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void send(UUID notificationId, String toAddress, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(FROM_ADDRESS);
        message.setTo(toAddress);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("Notification {} sent", notificationId);
        } catch (Exception e) {
            log.warn("Notification {} could not be sent", notificationId);
            throw e;
        }
    }
}
