package com.example.saas.api.auth;

import com.example.saas.api.TestcontainersConfiguration;
import com.example.saas.api.auth.outbox.OutboxWorker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for tests that run the real application against PostgreSQL with the outbox worker driven by hand and
 * email captured in memory. All subclasses share one Spring context and one database, so each test uses
 * its own email addresses and client address.
 */
@Import({ TestcontainersConfiguration.class, AuthTestConfiguration.class })
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AuthIntegrationTest {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected OutboxWorker worker;

	@Autowired
	protected RecordingEmailSender mail;

	protected AuthApi api;

	@BeforeEach
	void resetOutboxAndMail() {
		api = new AuthApi(mvc);
		// Deliver anything left over from other tests, then forget it.
		jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second', locked_until = NULL, "
				+ "status = 'PENDING' WHERE status IN ('PENDING', 'IN_FLIGHT')");
		drainOutbox();
		mail.clear();
	}

	/** Processes outbox events until none are due. */
	protected int drainOutbox() {
		int total = 0;
		int processed;
		do {
			processed = worker.runOnce();
			total += processed;
		}
		while (processed > 0);
		return total;
	}

	/** Signs up and verifies an account, as a real user would, and returns its ID. */
	protected java.util.UUID registerVerified(String email, String password) throws Exception {
		api.signup(email, password, "Verified Person", AuthApi.freshIp());
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		api.verify(token, AuthApi.freshIp());
		mail.clear();
		return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", java.util.UUID.class, email);
	}

	/** Signs up without verifying. */
	protected java.util.UUID registerUnverified(String email, String password) throws Exception {
		api.signup(email, password, "Unverified Person", AuthApi.freshIp());
		drainOutbox();
		mail.clear();
		return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", java.util.UUID.class, email);
	}

	/** Moves a user's stored throttle state back in time, as if that long had passed. */
	protected void ageThrottle(java.time.Duration elapsed) {
		long seconds = elapsed.toSeconds();
		jdbc.update("UPDATE login_throttle SET window_start = window_start - make_interval(secs => ?), "
				+ "last_failure_at = last_failure_at - make_interval(secs => ?), "
				+ "expires_at = expires_at - make_interval(secs => ?)", seconds, seconds, seconds);
	}

	protected int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

}
