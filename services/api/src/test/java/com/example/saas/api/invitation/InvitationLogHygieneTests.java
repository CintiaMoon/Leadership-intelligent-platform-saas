package com.example.saas.api.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.RecordingEmailSender;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-INV-07: a raw invitation token is not logged, at TRACE level, on the whole journey (create, deliver, resend, a
 * failed and a successful acceptance, a replay, and a signed-out attempt). Session and CSRF values must stay out of the
 * logs too.
 *
 * A token sent in a query string is a client mistake (the API reads it from the body only, AC-INV-18) and is covered
 * separately: Spring Security's own DEBUG line "Securing POST /path?query" would quote it, so the guard is that no
 * framework logger is raised above its default level outside tests.
 */
class InvitationLogHygieneTests extends AuthIntegrationTest {

	@Autowired
	private InvitationService service;

	@Autowired
	private TransactionTemplate tx;

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private final List<Logger> raised = new ArrayList<>();

	private final List<Level> previous = new ArrayList<>();

	@BeforeEach
	void captureLogs() {
		appender.start();
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		root.addAppender(appender);
	}

	private void traceEverything() {
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

	private static String text(ILoggingEvent event) {
		return event.getFormattedMessage() + " " + event.getMDCPropertyMap() + " "
				+ (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
	}

	private void assertNoneLogged(String... secrets) {
		assertThat(appender.list).as("logging must have happened for this test to mean anything").isNotEmpty();
		for (ILoggingEvent event : appender.list) {
			for (String secret : secrets) {
				assertThat(text(event)).as("log line from %s", event.getLoggerName()).doesNotContain(secret);
			}
		}
	}

	@Test
	void noRawInvitationTokenReachesTheLogsOnAnyStepOfTheJourney() throws Exception {
		traceEverything();
		String ownerEmail = AuthApi.uniqueEmail();
		UUID ownerId = registerVerified(ownerEmail, STRONG_PASSWORD);
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Log Co', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c", UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, ownerId);
			return id;
		});
		Instant now = Instant.now();
		AuthenticatedUser owner = new AuthenticatedUser(ownerId, now, now.plus(Duration.ofHours(12)));

		String inviteeEmail = "guest." + UUID.randomUUID() + "@example.com";
		UUID invitationId = service.create(owner, companyId, inviteeEmail, Role.VIEWER).id();
		drainOutbox();
		String first = RecordingEmailSender.tokenIn(mail.sentTo(inviteeEmail).get(0));
		mail.clear();

		service.resend(owner, companyId, invitationId);
		drainOutbox();
		String second = RecordingEmailSender.tokenIn(mail.sentTo(inviteeEmail).get(0));
		mail.clear();
		assertThat(second).isNotEqualTo(first);

		UUID inviteeId = registerVerified(inviteeEmail, STRONG_PASSWORD);
		Cookie invitee = sessionCookie(api.login(inviteeEmail, STRONG_PASSWORD, freshIp()));
		String ip = freshIp();
		String csrf = api.sessionCsrf(invitee, ip);

		String guess = "guessed-token-" + "g".repeat(29);
		api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", guess), invitee, csrf, ip);
		api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", first), invitee, csrf, ip);
		assertThat(api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", second), invitee, csrf, ip)
			.getResponse()
			.getStatus()).isEqualTo(200);
		api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", second), invitee, csrf, ip);
		// Signed out, with a real-looking token.
		api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", second), null, csrf, ip);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", Integer.class,
				companyId, inviteeId)).as("the journey really ran").isEqualTo(1);
		assertNoneLogged(first, second, guess, invitee.getValue(), csrf, STRONG_PASSWORD);
	}

	@Test
	void aTokenSentInTheQueryStringIsNotLoggedAtTheLevelsUsedOutsideTests() throws Exception {
		String ownerEmail = AuthApi.uniqueEmail();
		registerVerified(ownerEmail, STRONG_PASSWORD);
		Cookie session = sessionCookie(api.login(ownerEmail, STRONG_PASSWORD, freshIp()));
		String ip = freshIp();
		String inQuery = "query-token-" + "q".repeat(31);

		var result = api.send(HttpMethod.POST, "/api/v1/invitations/accept?token=" + inQuery, json("note", "x"), session,
				api.sessionCsrf(session, ip), ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).doesNotContain(inQuery);
		for (String name : List.of("org.springframework.web", "org.springframework.security")) {
			Logger logger = (Logger) LoggerFactory.getLogger(name);
			assertThat(logger.getEffectiveLevel().isGreaterOrEqual(Level.INFO)).as("%s is not raised to DEBUG", name).isTrue();
		}
		// Nothing may be logged for this request at these levels, so there is no "something was logged" guard here: the
		// response check above shows the request was handled.
		for (ILoggingEvent event : appender.list) {
			assertThat(text(event)).as("log line from %s", event.getLoggerName()).doesNotContain(inQuery);
		}
	}

}
