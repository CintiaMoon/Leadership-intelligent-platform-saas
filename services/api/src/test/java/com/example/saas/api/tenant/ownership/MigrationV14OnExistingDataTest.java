package com.example.saas.api.tenant.ownership;

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
 * Migration V14 applied to a database that already holds data written under V1 to V13 (plan, section 9): it only adds a
 * table, so nothing is lost or rewritten, the new table starts empty, and every constraint and index is in place and
 * validated. Uses its own container, so no Spring context is involved.
 */
class MigrationV14OnExistingDataTest {

	@Test
	void v14KeepsExistingRowsAndAddsAnEmptyValidatedTable() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))) {
			postgres.start();
			Flyway upToV13 = Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target("13")
				.load();
			upToV13.migrate();

			try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO app_user (id, email_normalized, email_original, display_name, "
						+ "password_hash, created_at, updated_at) VALUES "
						+ "('00000000-0000-0000-0000-0000000000a1', 'a@example.com', 'a@example.com', 'A', 'h', now(), now()), "
						+ "('00000000-0000-0000-0000-0000000000a2', 'b@example.com', 'b@example.com', 'B', 'h', now(), now())");
				connection.setAutoCommit(false);
				statement.execute("INSERT INTO company (id, name, created_at, updated_at) VALUES "
						+ "('00000000-0000-0000-0000-0000000000c1', 'Old Co', now(), now())");
				statement.execute("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) VALUES "
						+ "('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000a1', 'OWNER', 'ACTIVE', now(), now()), "
						+ "('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000a2', 'ADMIN', 'ACTIVE', now(), now())");
				statement.execute("INSERT INTO invitation (company_id, email_normalized, email_original, role, status, "
						+ "invited_by, expires_at, created_at, updated_at) VALUES "
						+ "('00000000-0000-0000-0000-0000000000c1', 'guest@example.com', 'guest@example.com', 'AGENT', 'PENDING', "
						+ "'00000000-0000-0000-0000-0000000000a1', now() + interval '7 days', now(), now())");
				connection.commit();
				connection.setAutoCommit(true);

				Flyway latest = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load();
				latest.migrate();
				latest.validate();

				assertThat(scalar(statement, "SELECT count(*) FROM company")).isEqualTo("1");
				assertThat(scalar(statement, "SELECT count(*) FROM membership")).isEqualTo("2");
				assertThat(scalar(statement, "SELECT count(*) FROM invitation")).isEqualTo("1");
				assertThat(scalar(statement, "SELECT count(*) FROM ownership_transfer")).as("the new table starts empty")
					.isEqualTo("0");
				assertThat(scalar(statement, "SELECT string_agg(convalidated::text, ',') FROM pg_constraint "
						+ "WHERE conrelid = 'ownership_transfer'::regclass AND contype IN ('c', 'f', 'p')"))
					.as("every constraint was validated").doesNotContain("false");
				assertThat(scalar(statement, "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_ownership_transfer_pending'"))
					.contains("UNIQUE").contains("WHERE");
				assertThat(scalar(statement, "SELECT count(*) FROM pg_indexes WHERE tablename = 'ownership_transfer'"))
					.as("primary key, pending, company, recipient and sweep indexes").isEqualTo("5");
				statement.execute("INSERT INTO ownership_transfer (company_id, initiated_by, recipient_id, created_at, "
						+ "expires_at, updated_at) VALUES ('00000000-0000-0000-0000-0000000000c1', "
						+ "'00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a2', now(), "
						+ "now() + interval '7 days', now())");
				assertThat(scalar(statement, "SELECT status FROM ownership_transfer")).isEqualTo("PENDING");
			}
		}
	}

	private static String scalar(Statement statement, String sql) throws Exception {
		try (ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getString(1);
		}
	}

}
