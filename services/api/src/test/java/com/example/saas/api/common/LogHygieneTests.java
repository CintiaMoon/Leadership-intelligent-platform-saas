package com.example.saas.api.common;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.saas.api.TestcontainersConfiguration;
import com.example.saas.api.security.TestEndpointsConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Regression guard for AC-AUTH-10: passwords, cookies, session identifiers, and the BFF credential must
 * not reach the logs, including at DEBUG level and on error paths where exception messages could quote
 * request content.
 */
@Import({ TestcontainersConfiguration.class, TestEndpointsConfiguration.class })
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LogHygieneTests {

	static final String BFF_CREDENTIAL = "test-bff-credential-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	static final List<String> SECRETS = List.of("hunter2-password-secret", "SESSIONVALUE-secret-cookie",
			"reset-token-secret-value", BFF_CREDENTIAL);

	@Autowired
	MockMvc mockMvc;

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private final List<Logger> raised = new ArrayList<>();

	private final List<Level> previous = new ArrayList<>();

	@BeforeEach
	void captureLogs() {
		appender.start();
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		root.addAppender(appender);
		for (String name : List.of("org.springframework.web", "org.springframework.security", "com.example.saas")) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			raised.add(logger);
			previous.add(logger.getLevel());
			logger.setLevel(Level.TRACE);
		}
	}

	@AfterEach
	void restoreLogs() {
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		root.detachAppender(appender);
		for (int i = 0; i < raised.size(); i++) {
			raised.get(i).setLevel(previous.get(i));
		}
	}

	private void assertNoSecretLogged() {
		assertThat(appender.list).as("some logging must have happened for this test to mean anything").isNotEmpty();
		for (ILoggingEvent event : appender.list) {
			String text = event.getFormattedMessage() + " " + event.getMDCPropertyMap() + " "
					+ (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
			for (String secret : SECRETS) {
				assertThat(text).as("log line from %s", event.getLoggerName()).doesNotContain(secret);
			}
		}
	}

	@Test
	void successfulAndRejectedRequestsDoNotLogSecrets() throws Exception {
		String body = "{\"name\":\"ok\",\"password\":\"hunter2-password-secret\"}";
		mockMvc.perform(post("/api/v1/test/echo")
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.cookie(new jakarta.servlet.http.Cookie("__Host-session", "SESSIONVALUE-secret-cookie"))
			.with(SecurityMockMvcRequestPostProcessors.csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
		// Wrong credential, rejected before anything else.
		mockMvc.perform(get("/api/v1/test/ping")
			.header("X-BFF-Credential", BFF_CREDENTIAL + "x")
			.cookie(new jakarta.servlet.http.Cookie("__Host-session", "SESSIONVALUE-secret-cookie")));
		assertNoSecretLogged();
	}

	@Test
	void malformedBodiesAndFailuresDoNotLogTheContent() throws Exception {
		mockMvc.perform(post("/api/v1/test/echo")
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.with(SecurityMockMvcRequestPostProcessors.csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\": hunter2-password-secret reset-token-secret-value"));
		mockMvc.perform(post("/api/v1/test/echo")
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.cookie(new jakarta.servlet.http.Cookie("__Host-session", "SESSIONVALUE-secret-cookie"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\",\"password\":\"hunter2-password-secret\"}"));
		mockMvc.perform(get("/api/v1/test/boom").header("X-BFF-Credential", BFF_CREDENTIAL));
		assertNoSecretLogged();
	}

}
