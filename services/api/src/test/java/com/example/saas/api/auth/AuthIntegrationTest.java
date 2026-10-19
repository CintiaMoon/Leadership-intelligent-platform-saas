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

	protected int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

}
