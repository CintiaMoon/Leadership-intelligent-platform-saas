package com.example.saas.api.tenant;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.saas.api.auth.AuthIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the company lifecycle, the tombstone and the company of an outbox event guarantee even if application code is
 * wrong (migration V17; SPEC-DEL AC-DEL-06, 09, 10, 22 at the schema). Every check is a statement run directly against
 * PostgreSQL.
 */
class CompanyLifecycleSchemaTests extends AuthIntegrationTest {

	private static final Instant REQUESTED = Instant.parse("2026-10-29T10:00:00Z");

	private static final Instant PURGE_AT = Instant.parse("2026-11-28T10:00:00Z");

	private static final List<String> STATES = List.of("ACTIVE", "PENDING_DELETION", "PURGE_QUEUED", "PURGING",
			"PURGE_FAILED");

	@Autowired
	private TransactionTemplate tx;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	/** A company with its Owner, ACTIVE: the database refuses a company without one at commit (V10). */
	private UUID newCompany() {
		return tx.execute(status -> {
			UUID owner = newUser();
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Lifecycle Co', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, owner);
			return id;
		});
	}

	private UUID ownerOf(UUID company) {
		return jdbc.queryForObject("SELECT user_id FROM membership WHERE company_id = ? AND role = 'OWNER'", UUID.class,
				company);
	}

	private static Timestamp ts(Instant instant) {
		return Timestamp.from(instant);
	}

	private String statusOf(UUID company) {
		return jdbc.queryForObject("SELECT status FROM company WHERE id = ?", String.class, company);
	}

	/**
	 * The UPDATE a move to this state would make, keeping the deletion request that is already recorded and recording
	 * one when there is none, with the other columns the state needs.
	 */
	private void moveTo(UUID company, String to) {
		UUID by = ownerOf(company);
		String request = "deletion_requested_at = COALESCE(deletion_requested_at, ?), "
				+ "deletion_requested_by = COALESCE(deletion_requested_by, ?), purge_at = COALESCE(purge_at, ?)";
		switch (to) {
			case "ACTIVE" -> jdbc.update("UPDATE company SET status = 'ACTIVE', deletion_requested_at = NULL, "
					+ "deletion_requested_by = NULL, purge_at = NULL, purge_attempts = 0, purge_next_attempt_at = NULL, "
					+ "purge_claimed_until = NULL, purge_error_code = NULL WHERE id = ?", company);
			case "PENDING_DELETION" -> jdbc.update("UPDATE company SET status = 'PENDING_DELETION', " + request
					+ " WHERE id = ?", ts(REQUESTED), by, ts(PURGE_AT), company);
			case "PURGE_QUEUED" -> jdbc.update("UPDATE company SET status = 'PURGE_QUEUED', " + request
					+ ", purge_claimed_until = NULL WHERE id = ?", ts(REQUESTED), by, ts(PURGE_AT), company);
			case "PURGING" -> jdbc.update("UPDATE company SET status = 'PURGING', " + request
					+ ", purge_claimed_until = ? WHERE id = ?", ts(REQUESTED), by, ts(PURGE_AT), ts(PURGE_AT.plusSeconds(60)),
					company);
			case "PURGE_FAILED" -> jdbc.update("UPDATE company SET status = 'PURGE_FAILED', " + request
					+ ", purge_claimed_until = NULL, purge_error_code = 'HANDLER_FAILED', purge_attempts = 1, "
					+ "purge_next_attempt_at = ? WHERE id = ?", ts(REQUESTED), by, ts(PURGE_AT), ts(PURGE_AT.plusSeconds(300)),
					company);
			default -> throw new IllegalArgumentException(to);
		}
	}

	/** A company in the given state, reached through the legal path. */
	private UUID companyIn(String state) {
		UUID company = newCompany();
		List<String> path = switch (state) {
			case "ACTIVE" -> List.of();
			case "PENDING_DELETION" -> List.of("PENDING_DELETION");
			case "PURGE_QUEUED" -> List.of("PENDING_DELETION", "PURGE_QUEUED");
			case "PURGING" -> List.of("PENDING_DELETION", "PURGE_QUEUED", "PURGING");
			case "PURGE_FAILED" -> List.of("PENDING_DELETION", "PURGE_QUEUED", "PURGING", "PURGE_FAILED");
			default -> throw new IllegalArgumentException(state);
		};
		path.forEach(step -> moveTo(company, step));
		assertThat(statusOf(company)).isEqualTo(state);
		return company;
	}

	private static boolean allowed(String from, String to) {
		return from.equals(to) || (from.equals("ACTIVE") && to.equals("PENDING_DELETION"))
				|| (from.equals("PENDING_DELETION") && (to.equals("ACTIVE") || to.equals("PURGE_QUEUED")))
				|| (from.equals("PURGE_QUEUED") && to.equals("PURGING"))
				|| (from.equals("PURGING") && to.equals("PURGE_FAILED"))
				|| (from.equals("PURGE_FAILED") && to.equals("PURGE_QUEUED"));
	}

	// ---- the states and their fields

	private void assertRefusedBy(UUID company, String set, String constraint, Object... args) {
		Object[] all = new Object[args.length + 1];
		System.arraycopy(args, 0, all, 0, args.length);
		all[args.length] = company;
		assertThatThrownBy(() -> jdbc.update("UPDATE company SET " + set + " WHERE id = ?", all))
			.as(set)
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining(constraint);
	}

	@Test
	void everyStateNeedsExactlyItsOwnFields() {
		UUID by = ownerOf(newCompany());
		assertRefusedBy(newCompany(), "status = 'PENDING_DELETION'", "ck_company_lifecycle_fields");
		assertRefusedBy(newCompany(), "status = 'PENDING_DELETION', deletion_requested_at = ?, deletion_requested_by = ?, "
				+ "purge_at = ?", "ck_company_lifecycle_fields", ts(REQUESTED), by, ts(REQUESTED));
		assertRefusedBy(newCompany(), "status = 'PENDING_DELETION', deletion_requested_at = ?, deletion_requested_by = ?, "
				+ "purge_at = ?, purge_attempts = 1", "ck_company_lifecycle_fields", ts(REQUESTED), by, ts(PURGE_AT));
		assertRefusedBy(newCompany(), "purge_at = ?", "ck_company_lifecycle_fields", ts(PURGE_AT));
		assertRefusedBy(newCompany(), "purge_claimed_until = ?", "ck_company_lifecycle_fields", ts(PURGE_AT));
		assertRefusedBy(companyIn("PENDING_DELETION"), "status = 'PURGE_QUEUED', purge_claimed_until = ?",
				"ck_company_lifecycle_fields", ts(PURGE_AT));
		assertRefusedBy(companyIn("PURGE_QUEUED"), "status = 'PURGING'", "ck_company_lifecycle_fields");
		assertRefusedBy(companyIn("PURGING"), "status = 'PURGE_FAILED', purge_claimed_until = NULL, purge_attempts = 1",
				"ck_company_lifecycle_fields");
		assertRefusedBy(companyIn("PURGING"), "status = 'PURGE_FAILED', purge_claimed_until = NULL, "
				+ "purge_error_code = 'HANDLER_FAILED'", "ck_company_lifecycle_fields");
		assertRefusedBy(companyIn("PURGING"), "status = 'PURGE_FAILED', purge_claimed_until = NULL, "
				+ "purge_error_code = 'HANDLER_FAILED', purge_attempts = 1, purge_hold_ticket = 'bad ticket!', "
				+ "purge_hold_set_at = now()", "ck_company_purge_hold");
	}

	@Test
	void theStatusValuesTheErrorCodeTheAttemptsAndTheHoldAreChecked() {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO company (name, status, created_at, updated_at) "
				+ "VALUES ('A', 'GONE', now(), now())")).isInstanceOf(DataAccessException.class)
			.as("refused by a check, whichever PostgreSQL evaluates first: the lifecycle CHECK also rejects it")
			.hasMessageContaining("violates check constraint");
		assertRefusedBy(companyIn("PURGING"), "status = 'PURGE_FAILED', purge_claimed_until = NULL, "
				+ "purge_error_code = 'lower case', purge_attempts = 1", "ck_company_purge_error_code");
		assertRefusedBy(companyIn("PURGING"), "status = 'PURGE_FAILED', purge_claimed_until = NULL, "
				+ "purge_error_code = 'HANDLER_FAILED', purge_attempts = 6", "ck_company_purge_attempts");
		assertRefusedBy(newCompany(), "purge_hold_ticket = 'OPS-1'", "ck_company_purge_hold");
		assertRefusedBy(newCompany(), "purge_hold_set_at = now()", "ck_company_purge_hold");
		assertRefusedBy(newCompany(), "purge_hold_ticket = 'has space', purge_hold_set_at = now()", "ck_company_purge_hold");
		assertRefusedBy(newCompany(), "purge_hold_ticket = ?, purge_hold_set_at = now()", "ck_company_purge_hold",
				"x".repeat(41));
	}

	// ---- the transitions

	@Test
	void everyPairOfStatesIsAllowedOrRefusedExactlyAsTheSpecSays() {
		List<String> wrong = new ArrayList<>();
		for (String from : STATES) {
			for (String to : STATES) {
				UUID company = companyIn(from);
				boolean accepted;
				try {
					moveTo(company, to);
					accepted = true;
				}
				catch (DataAccessException refused) {
					accepted = false;
				}
				if (accepted != allowed(from, to)) {
					wrong.add(from + " to " + to + (accepted ? " was accepted" : " was refused"));
				}
			}
		}
		assertThat(wrong).isEmpty();
	}

	@Test
	void anUnknownTransitionIsRefusedByTheTriggerWithItsOwnMessage() {
		UUID company = companyIn("PURGE_QUEUED");
		assertThatThrownBy(() -> moveTo(company, "ACTIVE")).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("PURGE_QUEUED to ACTIVE is not allowed");
	}

	@Test
	void theDeletionRequestNeverChangesOnceRecordedAndOnlyACancellationClearsIt() {
		UUID company = companyIn("PENDING_DELETION");
		UUID other = newUser();
		assertRefusedBy(company, "purge_at = ?", "never changes", ts(PURGE_AT.plusSeconds(3600)));
		assertRefusedBy(company, "deletion_requested_at = ?", "never changes", ts(REQUESTED.minusSeconds(60)));
		assertRefusedBy(company, "deletion_requested_by = ?", "never changes", other);
		assertRefusedBy(company, "status = 'PURGE_QUEUED', purge_at = ?, purge_claimed_until = NULL", "never changes",
				ts(PURGE_AT.plusSeconds(3600)));

		moveTo(company, "ACTIVE");
		assertThat(jdbc.queryForMap("SELECT deletion_requested_at, deletion_requested_by, purge_at FROM company "
				+ "WHERE id = ?", company).values()).containsOnlyNulls();

		assertThatCode(() -> moveTo(company, "PENDING_DELETION")).as("a cancelled company can ask again")
			.doesNotThrowAnyException();
		assertRefusedBy(companyIn("PURGING"), "purge_at = ?", "never changes", ts(PURGE_AT.plusSeconds(1)));
		assertRefusedBy(companyIn("PURGE_FAILED"), "deletion_requested_by = ?", "never changes", other);
	}

	@Test
	void aHoldCanBeSetAndClearedInEveryStateAndNeverMovesTheLifecycle() {
		for (String state : STATES) {
			UUID company = companyIn(state);
			jdbc.update("UPDATE company SET purge_hold_ticket = 'OPS-1', purge_hold_set_at = now() WHERE id = ?", company);
			assertThat(statusOf(company)).as(state).isEqualTo(state);
			assertThat(jdbc.queryForObject("SELECT purge_hold_ticket FROM company WHERE id = ?", String.class, company))
				.isEqualTo("OPS-1");
			jdbc.update("UPDATE company SET purge_hold_ticket = NULL, purge_hold_set_at = NULL WHERE id = ?", company);
			assertThat(jdbc.queryForObject("SELECT purge_hold_ticket FROM company WHERE id = ?", String.class, company))
				.isNull();
		}
	}

	@Test
	void theWorkersThreeQuestionsAreServedByPartialIndexes() {
		assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'company' "
				+ "AND indexname IN ('ix_company_purge_due', 'ix_company_purge_retry', 'ix_company_purge_lease')",
				String.class)).hasSize(3);
	}

	// ---- the tombstone

	@Test
	void theTombstoneHoldsTheCompanyIdAndTwoTimestampsAndNothingElse() {
		assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_name = 'company_tombstone' ORDER BY ordinal_position", String.class))
			.containsExactly("company_id", "deletion_requested_at", "purged_at");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'company_tombstone'::regclass "
				+ "AND contype = 'f'", Integer.class)).as("no foreign key: it outlives the company").isZero();
	}

	@Test
	void aTombstoneIsInsertOnly() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO company_tombstone (company_id, deletion_requested_at, purged_at) VALUES (?, ?, ?)", id,
				ts(REQUESTED), ts(PURGE_AT));

		assertThatThrownBy(() -> jdbc.update("UPDATE company_tombstone SET purged_at = now() WHERE company_id = ?", id))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("insert-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM company_tombstone WHERE company_id = ?", id))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("insert-only");
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.execute("TRUNCATE company_tombstone")))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("insert-only");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO company_tombstone (company_id, deletion_requested_at, purged_at) "
				+ "VALUES (?, ?, ?)", id, ts(REQUESTED), ts(PURGE_AT))).as("one tombstone per company")
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO company_tombstone (company_id, deletion_requested_at, purged_at) "
				+ "VALUES (?, ?, ?)", UUID.randomUUID(), ts(PURGE_AT), ts(REQUESTED))).as("purged after it was requested")
			.isInstanceOf(DataAccessException.class).hasMessageContaining("ck_company_tombstone_order");
		assertThat(count("SELECT count(*) FROM company_tombstone WHERE company_id = ?", id)).isEqualTo(1);
	}

	@Test
	void aTombstoneForACompanyThatStillExistsIsRefusedAtCommit() {
		UUID company = newCompany();
		assertThatThrownBy(() -> jdbc.update("INSERT INTO company_tombstone (company_id, deletion_requested_at, purged_at) "
				+ "VALUES (?, ?, ?)", company, ts(REQUESTED), ts(PURGE_AT))).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("still exists");
		assertThat(count("SELECT count(*) FROM company_tombstone WHERE company_id = ?", company)).isZero();
	}

	@Test
	void aPurgeThatWritesTheTombstoneAndDeletesTheCompanyInOneTransactionCommits() {
		UUID company = newCompany();

		tx.executeWithoutResult(s -> {
			jdbc.update("INSERT INTO company_tombstone (company_id, deletion_requested_at, purged_at) VALUES (?, ?, ?)",
					company, ts(REQUESTED), ts(PURGE_AT));
			jdbc.update("DELETE FROM membership WHERE company_id = ?", company);
			jdbc.update("DELETE FROM company WHERE id = ?", company);
		});

		assertThat(count("SELECT count(*) FROM company WHERE id = ?", company)).isZero();
		assertThat(count("SELECT count(*) FROM company_tombstone WHERE company_id = ?", company)).isEqualTo(1);
	}

	// ---- the company of an outbox event

	/** The rows these tests write have payloads the real outbox worker could not read, so they never stay. */
	@AfterEach
	void removeTestEvents() {
		jdbc.update("DELETE FROM outbox_event WHERE event_type = 'TEST_EVENT'");
	}

	private void outbox(String payload, UUID explicitCompany) {
		jdbc.update("INSERT INTO outbox_event (event_type, payload, idempotency_key, next_attempt_at, created_at, company_id) "
				+ "VALUES ('TEST_EVENT', ?::jsonb, ?, now(), now(), ?)", payload, UUID.randomUUID().toString(), explicitCompany);
	}

	private UUID companyOfLatestEvent() {
		return jdbc.queryForObject("SELECT company_id FROM outbox_event ORDER BY created_at DESC, id DESC LIMIT 1",
				UUID.class);
	}

	@Test
	void anOutboxEventTakesItsCompanyFromThePayloadWhenItHasOne() {
		UUID company = UUID.randomUUID();
		String id = "ev-" + UUID.randomUUID();

		jdbc.update("INSERT INTO outbox_event (event_type, payload, idempotency_key, next_attempt_at, created_at) "
				+ "VALUES ('TEST_EVENT', ?::jsonb, ?, now(), now())", "{\"companyId\": \"" + company.toString().toUpperCase()
				+ "\", \"other\": \"x\"}", id);

		assertThat(jdbc.queryForObject("SELECT company_id FROM outbox_event WHERE idempotency_key = ?", UUID.class, id))
			.isEqualTo(company);
	}

	@Test
	void anOutboxEventWithoutAUsableCompanyKeepsNull() {
		String[] payloads = { "{\"userId\": \"" + UUID.randomUUID() + "\"}", "{\"companyId\": \"not-a-uuid\"}",
				"{\"companyId\": 42}", "{\"companyId\": null}", "[]", "\"text\"" };
		for (String payload : payloads) {
			outbox(payload, null);
			assertThat(companyOfLatestEvent()).as(payload).isNull();
		}
	}

	@Test
	void anExplicitCompanyIsKept() {
		UUID explicit = UUID.randomUUID();
		outbox("{\"companyId\": \"" + UUID.randomUUID() + "\"}", explicit);
		assertThat(companyOfLatestEvent()).isEqualTo(explicit);
	}

	@Test
	void theCompanyOfAnOutboxEventIsIndexedAndHasNoForeignKey() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_outbox_event_company'",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'outbox_event'::regclass "
				+ "AND contype = 'f'", Integer.class)).isZero();
	}

}
