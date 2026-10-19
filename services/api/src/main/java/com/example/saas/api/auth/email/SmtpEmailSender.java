package com.example.saas.api.auth.email;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP delivery: Mailpit locally and in CI end-to-end tests, and the SES SMTP interface later (D-84).
 */
@Component
class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;

	private final AppProperties properties;

	SmtpEmailSender(JavaMailSender mailSender, AppProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@Override
	public void send(EmailMessage message) {
		try {
			MimeMessage mime = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
			helper.setFrom(properties.mail().from());
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			helper.setText(message.body(), false);
			mailSender.send(mime);
		}
		catch (jakarta.mail.MessagingException ex) {
			throw new EmailDeliveryException(ex);
		}
	}

}
