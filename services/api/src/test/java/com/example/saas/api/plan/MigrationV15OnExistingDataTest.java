package com.example.saas.api.plan;

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
 * Migration V15 applied to a database that already holds data written under V1 to V14 (plan, section 9; AC-PLAN-23,
 * D-199): every existing company becomes NO_PLAN, a user who already created a company has used the first-company slot,
 * a company with no creation event grants nothing, nothing existing is rewritten, and every constraint is validated.
 * Uses its own container, so no Spring context is involved.
 */
class MigrationV15OnExistingDataTest {

	private static final String USER_A = "00000000-0000-0000-0000-0000000000a1";

	private static final String USER_B = "00000000-0000-0000-0000-0000000000a2";

	private static final String USER_C = "00000000-0000-0000-0000-0000000000a3";

	private static final String COMPANY_1 = "00000000-0000-0000-0000-0000000000c1";

	private static final String COMPANY_2 = "00000000-0000-0000-0000-0000000000c2";

	private static final String COMPANY_3 = "00000000-0000-0000-0000-0000000000c3";

	private static final String COMPANY_4 = "00000000-0000-0000-0000-0000000000c4";

	@Test
	void v15GivesEveryExistingCompanyNoPlanAndRecordsWhoHasAlreadyCreatedOne() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"))) {
			postgres.start();
			Flyway upToV14 = Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target("14")
				.load();
			upToV14.migrate();

			try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO app_user (id, email_normalized, email_original, display_name, "
						+ "password_hash, created_at, updated_at) VALUES "
						+ "('" + USER_A + "', 'a@example.com', 'a@example.com', 'A', 'h', now(), now()), "
						+ "('" + USER_B + "', 'b@example.com', 'b@example.com', 'B', 'h', now(), now()), "
						+ "('" + USER_C + "', 'c@example.com', 'c@example.com', 'C', 'h', now(), now())");
				connection.setAutoCommit(false);
				statement.execute("INSERT INTO company (id, name, status, created_at, updated_at) VALUES "
						+ "('" + COMPANY_1 + "', 'First of A', 'ACTIVE', now(), now()), "
						+ "('" + COMPANY_2 + "', 'Second of A', 'ACTIVE', now(), now()), "
						+ "('" + COMPANY_3 + "', 'Only of B', 'PENDING_DELETION', now(), now()), "
						+ "('" + COMPANY_4 + "', 'Raw insert', 'ACTIVE', now(), now())");
				statement.execute("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) VALUES "
						+ "('" + COMPANY_1 + "', '" + USER_A + "', 'OWNER', 'ACTIVE', now(), now()), "
						+ "('" + COMPANY_2 + "', '" + USER_A + "', 'OWNER', 'ACTIVE', now(), now()), "
						+ "('" + COMPANY_3 + "', '" + USER_B + "', 'OWNER', 'ACTIVE', now(), now()), "
						+ "('" + COMPANY_4 + "', '" + USER_C + "', 'OWNER', 'ACTIVE', now(), now())");
				// The creation events: A created company 1 first (a day ago) and company 2 later; B created company 3;
				// nothing was recorded for company 4, and a company-created event by the system is not a user's, even if it names one.
				statement.execute("INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, actor_id, "
						+ "target_type, target_id, outcome) VALUES "
						+ "(now() - interval '1 day', 'tenant.company.created', '" + COMPANY_1 + "', 'user', '" + USER_A
						+ "', 'company', '" + COMPANY_1 + "', 'success'), "
						+ "(now() - interval '1 hour', 'tenant.company.created', '" + COMPANY_2 + "', 'user', '" + USER_A
						+ "', 'company', '" + COMPANY_2 + "', 'success'), "
						+ "(now() - interval '2 days', 'tenant.company.created', '" + COMPANY_3 + "', 'user', '" + USER_B
						+ "', 'company', '" + COMPANY_3 + "', 'success'), "
						+ "(now(), 'tenant.company.created', '" + COMPANY_4 + "', 'system', '" + USER_C + "', 'company', '" + COMPANY_4
						+ "', 'success'), "
						+ "(now(), 'tenant.membership.created', '" + COMPANY_4 + "', 'user', '" + USER_C
						+ "', 'membership', '" + COMPANY_4 + "', 'success')");
				connection.commit();
				connection.setAutoCommit(true);

				Flyway latest = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load();
				latest.migrate();
				latest.validate();

				assertThat(scalar(statement, "SELECT count(*) FROM company")).as("nothing was lost").isEqualTo("4");
				assertThat(scalar(statement, "SELECT count(*) FROM membership")).isEqualTo("4");
				assertThat(scalar(statement, "SELECT count(*) FROM audit_event")).isEqualTo("5");

				assertThat(scalar(statement, "SELECT count(*) FROM company_subscription")).as("one for every company")
					.isEqualTo("4");
				assertThat(scalar(statement, "SELECT count(*) FROM company_subscription WHERE state = 'NO_PLAN' "
						+ "AND plan_id IS NULL AND trial_started_at IS NULL AND trial_ends_at IS NULL "
						+ "AND read_only_ends_at IS NULL")).as("all of them NO_PLAN, with no plan and no trial").isEqualTo("4");
				assertThat(scalar(statement, "SELECT count(*) FROM company c JOIN company_subscription s "
						+ "ON s.company_id = c.id WHERE c.status = 'PENDING_DELETION'"))
					.as("a company that is pending deletion gets one too").isEqualTo("1");

				assertThat(scalar(statement, "SELECT string_agg(user_id::text || '>' || company_id::text, ',' ORDER BY user_id) "
						+ "FROM user_trial_grant"))
					.as("the creators of the earliest recorded companies, and nobody else")
					.isEqualTo(USER_A + ">" + COMPANY_1 + "," + USER_B + ">" + COMPANY_3);

				assertThat(scalar(statement, "SELECT count(*) FROM plan")).isEqualTo("3");
				assertThat(scalar(statement, "SELECT count(*) FROM plan_limit")).isEqualTo("9");
				assertThat(scalar(statement, "SELECT count(*) FROM plan_feature")).isEqualTo("20");
				assertThat(scalar(statement, "SELECT string_agg(convalidated::text, ',') FROM pg_constraint "
						+ "WHERE conrelid IN ('plan'::regclass, 'plan_limit'::regclass, 'plan_feature'::regclass, "
						+ "'company_subscription'::regclass, 'user_trial_grant'::regclass) AND contype IN ('c', 'f', 'p', 'u')"))
					.as("every constraint was validated").doesNotContain("false");
			}
		}
	}

	@Test
	void v15OnAnEmptyDatabaseCreatesTheCatalogAndNothingElse() throws Exception {
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
				assertThat(scalar(statement, "SELECT count(*) FROM plan")).isEqualTo("3");
				assertThat(scalar(statement, "SELECT count(*) FROM company_subscription")).isEqualTo("0");
				assertThat(scalar(statement, "SELECT count(*) FROM user_trial_grant")).isEqualTo("0");
			}
		}
	}

	private static String scalar(Statement statement, String sql) throws Exception {
		try (ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
	}

}
