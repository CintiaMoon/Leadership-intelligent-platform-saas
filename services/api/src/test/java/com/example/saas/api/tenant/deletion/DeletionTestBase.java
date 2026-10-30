package com.example.saas.api.tenant.deletion;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.saas.api.MutableClock;
import com.example.saas.api.MutableClockConfiguration;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.deletion.application.DeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A company with an Owner and one member of each other role, all with verified, ACTIVE accounts, and a clock the test
 * controls. Importing the clock gives these tests their own Spring context. Every test builds its own company and its own
 * people, so the per-user and per-company limits of one test never touch another.
 */
@Import(MutableClockConfiguration.class)
abstract class DeletionTestBase extends AuthIntegrationTest {

	@Autowired
	protected DeletionService service;

	@Autowired
	protected MutableClock clock;

	@Autowired
	protected TransactionTemplate tx;

	@BeforeEach
	void freezeTheClock() {
		clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	protected record Person(UUID userId, String email, String name) {
	}

	protected record Team(UUID companyId, Person owner, Person admin, Person agent, Person viewer, UUID ownerSeat,
			UUID adminSeat, UUID agentSeat, UUID viewerSeat) {
	}

	protected Person person(String name) {
		String email = "member." + UUID.randomUUID() + "@example.com";
		UUID id = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "email_verified_at, created_at, updated_at) VALUES (?, ?, ?, 'hash', now(), now(), now()) RETURNING id",
				UUID.class, email, email, name);
		return new Person(id, email, name);
	}

	protected UUID seat(UUID companyId, Person person, String role, String status) {
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, person.userId(), role, status);
	}

	protected Team team() {
		return tx.execute(s -> {
			Person owner = person("Olivia Owner");
			Person admin = person("Bo Admin");
			Person agent = person("Cy Agent");
			Person viewer = person("Di Viewer");
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Closing Co', now(), now()) RETURNING id), "
							+ "s AS (INSERT INTO company_subscription (company_id, plan_id, state, state_changed_at, created_at, updated_at) "
							+ "SELECT c.id, (SELECT id FROM plan WHERE code = 'TEAM' AND version = 1), 'ACTIVE', now(), now(), now() FROM c) "
							+ "SELECT id FROM c",
					UUID.class);
			return new Team(id, owner, admin, agent, viewer, seat(id, owner, "OWNER", "ACTIVE"),
					seat(id, admin, "ADMIN", "ACTIVE"), seat(id, agent, "AGENT", "ACTIVE"), seat(id, viewer, "VIEWER", "ACTIVE"));
		});
	}

	/** A person signed in just now. */
	protected AuthenticatedUser session(Person person) {
		return new AuthenticatedUser(person.userId(), clock.instant(), clock.instant().plus(Duration.ofHours(12)));
	}

	/** A person whose last login was 11 minutes ago: too old for the sensitive operations (D-15). */
	protected AuthenticatedUser staleSession(Person person) {
		return new AuthenticatedUser(person.userId(), clock.instant().minus(Duration.ofMinutes(11)),
				clock.instant().plus(Duration.ofHours(12)));
	}

	protected String statusOf(UUID companyId) {
		return jdbc.queryForObject("SELECT status FROM company WHERE id = ?", String.class, companyId);
	}

	protected int audits(String eventType, UUID companyId) {
		return count("SELECT count(*) FROM audit_event WHERE event_type = ? AND company_id = ?", eventType, companyId);
	}

	protected int events(String eventType, UUID companyId) {
		return count("SELECT count(*) FROM outbox_event WHERE event_type = ? AND company_id = ?", eventType, companyId);
	}

	protected int eventsTo(String eventType, UUID companyId, Person person) {
		return count("SELECT count(*) FROM outbox_event WHERE event_type = ? AND company_id = ? AND payload->>'userId' = ?",
				eventType, companyId, person.userId().toString());
	}

}
