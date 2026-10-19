package com.example.saas.api.auth;

import java.util.Optional;

import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.outbox.OutboxEvent;
import com.example.saas.api.auth.outbox.OutboxHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces SMTP delivery with an in-memory recorder and adds handlers used to exercise the outbox worker. */
@TestConfiguration(proxyBeanMethods = false)
public class AuthTestConfiguration {

	public static final String TEST_OK = "TEST_OK";

	public static final String TEST_FAIL = "TEST_FAIL";

	@Bean
	@Primary
	RecordingEmailSender recordingEmailSender() {
		return new RecordingEmailSender();
	}

	/** Sends one email to the address in the payload. */
	@Bean
	OutboxHandler okHandler() {
		return new OutboxHandler() {

			@Override
			public String eventType() {
				return TEST_OK;
			}

			@Override
			public Optional<EmailMessage> handle(OutboxEvent event) {
				return Optional.of(new EmailMessage(event.payload().get("to"), "test", "body"));
			}

		};
	}

	/** Always fails, to exercise retries. */
	@Bean
	OutboxHandler failingHandler() {
		return new OutboxHandler() {

			@Override
			public String eventType() {
				return TEST_FAIL;
			}

			@Override
			public Optional<EmailMessage> handle(OutboxEvent event) {
				throw new IllegalStateException("boom");
			}

		};
	}

}
