package com.example.saas.api.auth.email;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SMTP adapter against an in-process mail server (D-84). */
class SmtpEmailSenderTest {

	@RegisterExtension
	static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

	private static AppProperties properties() {
		return new AppProperties("https://app.test.example", new AppProperties.Mail("no-reply@liptech.io"));
	}

	private static SmtpEmailSender sender(String host, int port) {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(host);
		mailSender.setPort(port);
		mailSender.setDefaultEncoding("UTF-8");
		return new SmtpEmailSender(mailSender, properties());
	}

	@Test
	void deliversASingleTextMessageWithTheConfiguredSender() throws Exception {
		SmtpEmailSender sender = sender("127.0.0.1", greenMail.getSmtp().getPort());
		AuthEmails emails = new AuthEmails(properties());

		sender.send(emails.verification("jose@example.com", "TOKENVALUE"));
		sender.send(emails.passwordChanged("jose@example.com", "José"));

		MimeMessage[] received = greenMail.getReceivedMessages();
		assertThat(received).hasSize(2);
		assertThat(received[1].getContent().toString()).as("non-ASCII text survives delivery").contains("Hello José");
		assertThat(received[0].getSubject()).isEqualTo("Verify your email address");
		assertThat(received[0].getFrom()[0].toString()).isEqualTo("no-reply@liptech.io");
		assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("jose@example.com");
		String body = received[0].getContent().toString();
		assertThat(received[0].getContentType()).startsWith("text/plain");
		assertThat(body).contains("https://app.test.example/verify-email#token=TOKENVALUE");
	}

	@Test
	void anUnreachableServerSurfacesAsAnExceptionSoTheOutboxRetries() {
		SmtpEmailSender sender = sender("127.0.0.1", 1);

		assertThatThrownBy(() -> sender.send(new EmailMessage("a@example.com", "s", "b")))
			.isInstanceOfAny(MailException.class, EmailDeliveryException.class);
	}

	@Test
	void messageToStringNeverContainsTheBodyOrRecipient() {
		EmailMessage message = new EmailMessage("secret@example.com", "Subject line", "link#token=SECRET");

		assertThat(message.toString()).doesNotContain("secret@example.com").doesNotContain("SECRET");
	}

	@Test
	void linksCarryTheTokenInTheFragmentOnly() {
		AuthEmails emails = new AuthEmails(properties());

		String body = emails.verification("a@example.com", "TOK").body();

		assertThat(body).contains("#token=TOK").doesNotContain("?token=").doesNotContain("/TOK");
	}

	@Test
	void theBaseUrlMustBeConfiguredAndWellFormed() {
		assertThatThrownBy(() -> new AppProperties(null, new AppProperties.Mail("a@b.co")))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new AppProperties("ftp://example.com", new AppProperties.Mail("a@b.co")))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new AppProperties("https://example.com/?x=1", new AppProperties.Mail("a@b.co")))
			.isInstanceOf(IllegalStateException.class);
		assertThat(new AppProperties("https://example.com/", new AppProperties.Mail("a@b.co")).publicBaseUrl())
			.isEqualTo("https://example.com");
	}

}
