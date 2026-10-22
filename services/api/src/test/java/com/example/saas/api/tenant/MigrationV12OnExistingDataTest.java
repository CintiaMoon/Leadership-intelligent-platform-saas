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
 * Migration V12 applied to a database that already holds data written under V1 to V11 (plan, section 8): nothing is
 * lost or rewritten, and the new columns start empty. Uses its own container, so no Spring context is involved.
 */
class MigrationV12OnExistingDataTest {

	@Test
	void v12KeepsExistingRowsAndLeavesTheNewColumnsEmpty() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))) {
			postgres.start();
			Flyway upToV11 = Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target("11")
				.load();
			upToV11.migrate();

			try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO app_user (id, email_normalized, email_original, display_name, "
						+ "password_hash, created_at, updated_at) VALUES "
						+ "('00000000-0000-0000-0000-0000000000a1', 'a@example.com', 'a@example.com', 'A', 'h', now(), now())");
				connection.setAutoCommit(false);
				statement.execute("INSERT INTO company (id, name, created_at, updated_at) VALUES "
						+ "('00000000-0000-0000-0000-0000000000c1', 'Old Co', now(), now())");
				statement.execute("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
						+ "VALUES ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000a1', "
						+ "'OWNER', 'ACTIVE', now(), now())");
				statement.execute("INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, "
						+ "target_type, outcome) VALUES (now(), 'tenant.company.created', "
						+ "'00000000-0000-0000-0000-0000000000c1', 'user', 'company', 'success')");
				connection.commit();
				connection.setAutoCommit(true);

				Flyway latest = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load();
				latest.migrate();
				latest.validate();

				assertThat(scalar(statement, "SELECT count(*) FROM company")).isEqualTo("1");
				assertThat(scalar(statement, "SELECT count(*) FROM membership WHERE role = 'OWNER' AND status = 'ACTIVE'"))
					.isEqualTo("1");
				assertThat(scalar(statement, "SELECT count(*) FROM audit_event")).isEqualTo("1");
				assertThat(scalar(statement, "SELECT status_changed_at IS NULL AND status_changed_by IS NULL FROM membership"))
					.isEqualTo("t");
				assertThat(scalar(statement, "SELECT ownership_resolution_at IS NULL FROM company")).isEqualTo("t");
				assertThat(scalar(statement, "SELECT before_value IS NULL AND after_value IS NULL FROM audit_event"))
					.isEqualTo("t");
				assertThat(scalar(statement, "SELECT convalidated::text FROM pg_constraint "
						+ "WHERE conname = 'ck_audit_event_after_value'")).as("the CHECK was validated").isEqualTo("true");
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
