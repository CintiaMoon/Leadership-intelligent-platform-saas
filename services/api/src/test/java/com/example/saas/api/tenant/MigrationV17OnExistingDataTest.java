package com.example.saas.api.tenant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration V17 applied to a database that already holds data written under V1 to V16 (slice 007 plan, Phase 1; Q-03,
 * Q-10): an ACTIVE company is untouched, a company that was set to PENDING_DELETION by hand gets the deletion request the
 * new CHECK demands from data it already has, outbox events with a company in their payload are attributed to it and the
 * others are left alone, and every constraint is validated. Uses its own container, so no Spring context is involved.
 */
class MigrationV17OnExistingDataTest {

	private static final String OWNER_A = "00000000-0000-0000-0000-0000000000a1";

	private static final String OWNER_B = "00000000-0000-0000-0000-0000000000a2";

	private static final String ACTIVE_COMPANY = "00000000-0000-0000-0000-0000000000c1";

	private static final String PENDING_COMPANY = "00000000-0000-0000-0000-0000000000c2";

	@Test
	void v17BackfillsAHandEditedPendingCompanyAndAttributesTheOutboxEventsThatNameACompany() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))) {
			postgres.start();
			Flyway upToV16 = Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target("16")
				.load();
			upToV16.migrate();

			try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO app_user (id, email_normalized, email_original, display_name, "
						+ "password_hash, created_at, updated_at) VALUES "
						+ "('" + OWNER_A + "', 'a@example.com', 'a@example.com', 'A', 'h', now(), now()), "
						+ "('" + OWNER_B + "', 'b@example.com', 'b@example.com', 'B', 'h', now(), now())");
				connection.setAutoCommit(false);
				statement.execute("INSERT INTO company (id, name, status, created_at, updated_at) VALUES "
						+ "('" + ACTIVE_COMPANY + "', 'Active', 'ACTIVE', now(), now()), "
						+ "('" + PENDING_COMPANY + "', 'Edited by hand', 'PENDING_DELETION', "
						+ "timestamptz '2026-10-01 10:00:00+00', timestamptz '2026-10-01 10:00:00+00')");
				statement.execute("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) VALUES "
						+ "('" + ACTIVE_COMPANY + "', '" + OWNER_A + "', 'OWNER', 'ACTIVE', now(), now()), "
						+ "('" + PENDING_COMPANY + "', '" + OWNER_B + "', 'OWNER', 'ACTIVE', now(), now())");
				connection.commit();
				connection.setAutoCommit(true);

				statement.execute("INSERT INTO outbox_event (event_type, payload, idempotency_key, next_attempt_at, created_at) "
						+ "VALUES "
						+ "('INVITATION_EMAIL', '{\"companyId\": \"" + ACTIVE_COMPANY + "\", \"invitationId\": \"x\"}', 'k1', now(), now()), "
						+ "('OWNERSHIP_TRANSFER', '{\"companyId\": \"" + PENDING_COMPANY.toUpperCase()
						+ "\", \"transferId\": \"y\"}', 'k2', now(), now()), "
						+ "('VERIFY_EMAIL', '{\"userId\": \"" + OWNER_A + "\"}', 'k3', now(), now()), "
						+ "('ODD', '{\"companyId\": \"not-a-uuid\"}', 'k4', now(), now()), "
						+ "('ODD', '{\"companyId\": 7}', 'k5', now(), now())");

				Flyway latest = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load();
				latest.migrate();
				latest.validate();

				assertThat(scalar(statement, "SELECT count(*) FROM company")).as("nothing was lost").isEqualTo("2");
				assertThat(scalar(statement, "SELECT status || ':' || coalesce(deletion_requested_at::text, '-') "
						+ "FROM company WHERE id = '" + ACTIVE_COMPANY + "'")).as("an ACTIVE company is untouched")
					.isEqualTo("ACTIVE:-");
				assertThat(scalar(statement, "SELECT status FROM company WHERE id = '" + PENDING_COMPANY + "'"))
					.isEqualTo("PENDING_DELETION");
				assertThat(scalar(statement, "SELECT deletion_requested_by::text FROM company WHERE id = '" + PENDING_COMPANY
						+ "'")).as("the actor is the Owner").isEqualTo(OWNER_B);
				assertThat(scalar(statement, "SELECT deletion_requested_at = updated_at FROM company WHERE id = '"
						+ PENDING_COMPANY + "'")).as("the request is its last update").isEqualTo("t");
				assertThat(scalar(statement, "SELECT purge_at - deletion_requested_at FROM company WHERE id = '"
						+ PENDING_COMPANY + "'")).as("30 days later").isEqualTo("30 days");

				assertThat(scalar(statement, "SELECT string_agg(idempotency_key || '>' || coalesce(company_id::text, '-'), ',' "
						+ "ORDER BY idempotency_key) FROM outbox_event"))
					.as("events that name a company are attributed to it (also in capitals), the others keep none")
					.isEqualTo("k1>" + ACTIVE_COMPANY + ",k2>" + PENDING_COMPANY + ",k3>-,k4>-,k5>-");
				assertThat(scalar(statement, "SELECT count(*) FROM outbox_event")).as("no event was lost").isEqualTo("5");

				assertThat(scalar(statement, "SELECT string_agg(convalidated::text, ',') FROM pg_constraint "
						+ "WHERE conrelid IN ('company'::regclass, 'company_tombstone'::regclass) "
						+ "AND contype IN ('c', 'f', 'p', 'u')")).as("every constraint was validated").doesNotContain("false");
			}
		}
	}

	@Test
	void v17OnAnEmptyDatabaseCreatesTheTombstoneAndTheColumnsAndNothingElse() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))) {
			postgres.start();
			Flyway flyway = Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.load();
			flyway.migrate();
			flyway.validate();

			try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement statement = connection.createStatement()) {
				assertThat(scalar(statement, "SELECT count(*) FROM company_tombstone")).isEqualTo("0");
				assertThat(scalar(statement, "SELECT count(*) FROM company")).isEqualTo("0");
				assertThat(scalar(statement, "SELECT count(*) FROM information_schema.columns "
						+ "WHERE table_name = 'company' AND column_name IN ('deletion_requested_at', 'deletion_requested_by', "
						+ "'purge_at', 'purge_attempts', 'purge_next_attempt_at', 'purge_claimed_until', 'purge_error_code', "
						+ "'purge_hold_ticket', 'purge_hold_set_at')")).isEqualTo("9");
			}
		}
	}

	private static String scalar(Statement statement, String sql) throws Exception {
		try (ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).as(sql).isTrue();
			return rows.getString(1);
		}
	}

}
