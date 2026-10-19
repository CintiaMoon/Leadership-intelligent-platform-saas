package com.example.saas.api.auth;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariants that hold at the database even if application code is wrong (CLAUDE.md principle 9). */
class SchemaConstraintTests extends AuthIntegrationTest {

	private static final String INSERT_USER = "INSERT INTO app_user (email_normalized, email_original, display_name, "
			+ "password_hash, status, created_at, updated_at) VALUES (?, ?, ?, 'hash', ?, now(), now())";

	private void insertUser(String normalized, String original, String name, String status) {
		jdbc.update(INSERT_USER, normalized, original, name, status);
	}

	@Test
	void emailsAreUniqueOnTheNormalizedForm() {
		String email = UUID.randomUUID() + "@example.com";
		insertUser(email, email, "A", "ACTIVE");

		assertThatThrownBy(() -> insertUser(email, email.toUpperCase(), "B", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theNormalizedEmailMustBeLowercase() {
		assertThatThrownBy(() -> insertUser("Mixed.Case@example.com", "x", "A", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void statusMustBeActiveOrDisabled() {
		assertThatThrownBy(() -> insertUser(UUID.randomUUID() + "@example.com", "x", "A", "LOCKED"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void displayNameLengthIsBounded() {
		assertThatThrownBy(() -> insertUser(UUID.randomUUID() + "@example.com", "x", "", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertUser(UUID.randomUUID() + "@example.com", "x", "y".repeat(101), "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void tokensNeedAKnownPurposeAHexHashAndAnUnknownUserIsRefused() {
		String email = UUID.randomUUID() + "@example.com";
		insertUser(email, email, "A", "ACTIVE");
		UUID userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", UUID.class, email);
		String insert = "INSERT INTO verification_token (user_id, purpose, token_hash, expires_at, created_at) "
				+ "VALUES (?, ?, ?, now() + interval '1 hour', now())";
		String hash = "a".repeat(64);

		assertThatThrownBy(() -> jdbc.update(insert, userId, "OTHER", hash))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(insert, userId, "EMAIL_VERIFICATION", "not-hex"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "EMAIL_VERIFICATION", hash))
			.isInstanceOf(DataIntegrityViolationException.class);

		jdbc.update(insert, userId, "EMAIL_VERIFICATION", hash);
		assertThatThrownBy(() -> jdbc.update(insert, userId, "EMAIL_VERIFICATION", hash))
			.as("a token hash can only exist once")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void auditEventsCannotBeUpdated() throws Exception {
		String email = UUID.randomUUID() + "@example.com";
		api.signup(email, AuthApi.STRONG_PASSWORD, "Audit Trigger", AuthApi.freshIp());
		UUID eventId = jdbc.queryForObject("SELECT a.id FROM audit_event a JOIN app_user u ON u.id = a.target_id "
				+ "WHERE u.email_normalized = ?", UUID.class, email);

		assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET outcome = 'denied' WHERE id = ?", eventId))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThat(jdbc.queryForObject("SELECT outcome FROM audit_event WHERE id = ?", String.class, eventId))
			.isEqualTo("success");
	}

	@Test
	void auditEventNamesFollowTheCatalogueFormat() {
		String insert = "INSERT INTO audit_event (occurred_at, event_type, actor_type, target_type, outcome) "
				+ "VALUES (now(), ?, 'system', 'user', 'success')";

		assertThatThrownBy(() -> jdbc.update(insert, "BadName")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(insert, "single")).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update(insert, "auth.test.event");
	}

	@Test
	void outboxStatusAndAttemptsAreChecked() {
		String insert = "INSERT INTO outbox_event (event_type, payload, idempotency_key, status, next_attempt_at, "
				+ "created_at) VALUES ('T', '{}'::jsonb, ?, ?, now(), now())";

		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "DONE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update(insert, UUID.randomUUID().toString(), "PENDING");
	}

}
