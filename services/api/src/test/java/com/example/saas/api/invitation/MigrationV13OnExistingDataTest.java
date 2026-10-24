package com.example.saas.api.invitation;

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
 * Migration V13 applied to a database that already holds data written under V1 to V12 (plan, section 8): nothing is
 * lost or rewritten and the new table starts empty. Uses its own container, so no Spring context is involved.
 */
class MigrationV13OnExistingDataTest {

	@Test
	void v13KeepsExistingRowsAndAddsAnEmptyInvitationTable() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))) {
			postgres.start();
			Flyway upToV12 = Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target("12")
				.load();
			upToV12.migrate();

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
				connection.commit();
				connection.setAutoCommit(true);

				Flyway latest = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load();
				latest.migrate();
				latest.validate();

				assertThat(scalar(statement, "SELECT count(*) FROM company")).isEqualTo("1");
				assertThat(scalar(statement, "SELECT count(*) FROM membership")).isEqualTo("1");
				assertThat(scalar(statement, "SELECT count(*) FROM invitation")).isEqualTo("0");
				statement.execute("INSERT INTO invitation (company_id, email_normalized, email_original, role, invited_by, "
						+ "expires_at, created_at, updated_at) VALUES ('00000000-0000-0000-0000-0000000000c1', "
						+ "'g@example.com', 'g@example.com', 'AGENT', '00000000-0000-0000-0000-0000000000a1', "
						+ "now() + interval '7 days', now(), now())");
				assertThat(scalar(statement, "SELECT count(*) FROM invitation")).isEqualTo("1");
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
