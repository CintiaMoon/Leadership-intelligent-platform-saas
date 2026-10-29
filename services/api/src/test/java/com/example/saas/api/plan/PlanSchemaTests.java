package com.example.saas.api.plan;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.Parallel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the plan catalog, the subscription table and the trial grant guarantee even if application code is wrong
 * (migration V15; AC-PLAN-02, 09, 15, 16, 17, 25). Every check is a statement run directly against PostgreSQL.
 */
class PlanSchemaTests extends AuthIntegrationTest {

	private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");

	private static final long TRIAL_HOURS = 14 * 24;

	@Autowired
	private TransactionTemplate tx;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	/** A company with its Owner: the database refuses a company without one at commit (V10). */
	private UUID newCompany() {
		return tx.execute(status -> {
			UUID owner = newUser();
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Plan Co', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, owner);
			return id;
		});
	}

	private UUID plan(String code) {
		return jdbc.queryForObject("SELECT id FROM plan WHERE code = ? AND version = 1", UUID.class, code);
	}

	private static Timestamp ts(Instant instant) {
		return Timestamp.from(instant);
	}

	private static Instant plusHours(Instant instant, long hours) {
		return instant.plusSeconds(hours * 3600);
	}

	/** A subscription row; nulls are NULLs. */
	private void insertSubscription(UUID companyId, UUID planId, String state, Instant started, Instant ends,
			Instant readOnlyEnds) {
		jdbc.update("INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, "
				+ "read_only_ends_at, state_changed_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now(), now())",
				companyId, planId, state, started == null ? null : ts(started), ends == null ? null : ts(ends),
				readOnlyEnds == null ? null : ts(readOnlyEnds));
	}

	private void insertTrialing(UUID companyId) {
		Instant ends = plusHours(START, TRIAL_HOURS);
		insertSubscription(companyId, plan("TRIAL"), "TRIALING", START, ends, plusHours(ends, TRIAL_HOURS));
	}

	private void insertNoPlan(UUID companyId) {
		insertSubscription(companyId, null, "NO_PLAN", null, null, null);
	}

	private void insertActive(UUID companyId, String planCode) {
		insertSubscription(companyId, plan(planCode), "ACTIVE", null, null, null);
	}

	private String stateOf(UUID companyId) {
		return jdbc.queryForObject("SELECT state FROM company_subscription WHERE company_id = ?", String.class, companyId);
	}

	// ---- the catalog

	private Map<String, Integer> limitsOf(String code) {
		Map<String, Integer> limits = new TreeMap<>();
		jdbc.query("SELECT l.limit_key, l.value FROM plan_limit l JOIN plan p ON p.id = l.plan_id "
				+ "WHERE p.code = ? AND p.version = 1", rs -> {
					limits.put(rs.getString(1), rs.getInt(2));
				}, code);
		return limits;
	}

	private List<String> featuresOf(String code) {
		return jdbc.queryForList("SELECT f.feature_key FROM plan_feature f JOIN plan p ON p.id = f.plan_id "
				+ "WHERE p.code = ? AND p.version = 1 ORDER BY f.feature_key", String.class, code);
	}

	@Test
	void theThreePlansAreSeededWithTheLimitsAndFeaturesOfTheSpec() {
		assertThat(jdbc.queryForList("SELECT code || ':' || version FROM plan ORDER BY code", String.class))
			.containsExactly("STARTER:1", "TEAM:1", "TRIAL:1");
		assertThat(limitsOf("TRIAL")).containsExactly(Map.entry("ACTIVE_ASSESSMENTS", 3), Map.entry("ACTIVE_MEMBERS", 15),
				Map.entry("TEAMS", 3));
		assertThat(limitsOf("STARTER")).containsExactly(Map.entry("ACTIVE_ASSESSMENTS", 2), Map.entry("ACTIVE_MEMBERS", 15),
				Map.entry("TEAMS", 3));
		assertThat(limitsOf("TEAM")).containsExactly(Map.entry("ACTIVE_ASSESSMENTS", 10), Map.entry("ACTIVE_MEMBERS", 100),
				Map.entry("TEAMS", 15));
		List<String> starter = List.of("CORE_SCORING", "CSV_EXPORT", "HISTORICAL_COMPARISONS", "REPORTS");
		List<String> team = List.of("ADVANCED_ANALYTICS", "ANOMALY_DETECTION", "BENCHMARKING", "CORE_SCORING", "CSV_EXPORT",
				"EXTENDED_HISTORICAL_REPORTING", "HISTORICAL_COMPARISONS", "REPORTS");
		assertThat(featuresOf("STARTER")).isEqualTo(starter);
		assertThat(featuresOf("TEAM")).isEqualTo(team);
		assertThat(featuresOf("TRIAL")).as("the Trial has the full Team feature set").isEqualTo(team);
	}

	@Test
	void theCatalogRefusesUnknownCodesKeysAndValues() {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO plan (code, version, name, created_at) VALUES ('GOLD', 1, 'Gold', now())"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO plan (code, version, name, created_at) VALUES ('TEAM', 0, 'Team', now())"))
			.as("version 1 or later").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO plan (code, version, name, created_at) VALUES ('TEAM', 1, 'Again', now())"))
			.as("a code and version exist once").isInstanceOf(DataAccessException.class);
		tx.executeWithoutResult(status -> {
			UUID version = jdbc.queryForObject(
					"INSERT INTO plan (code, version, name, created_at) VALUES ('STARTER', 2, 'Starter', now()) RETURNING id",
					UUID.class);
			assertThatThrownBy(() -> jdbc.update("INSERT INTO plan_limit (plan_id, limit_key, value) VALUES (?, 'SEATS', 1)", version))
				.isInstanceOf(DataAccessException.class);
			status.setRollbackOnly();
		});
		tx.executeWithoutResult(status -> {
			UUID version = jdbc.queryForObject(
					"INSERT INTO plan (code, version, name, created_at) VALUES ('STARTER', 2, 'Starter', now()) RETURNING id",
					UUID.class);
			assertThatThrownBy(() -> jdbc.update("INSERT INTO plan_limit (plan_id, limit_key, value) VALUES (?, 'TEAMS', -1)", version))
				.as("a limit is not negative").isInstanceOf(DataAccessException.class);
			status.setRollbackOnly();
		});
		tx.executeWithoutResult(status -> {
			UUID version = jdbc.queryForObject(
					"INSERT INTO plan (code, version, name, created_at) VALUES ('STARTER', 2, 'Starter', now()) RETURNING id",
					UUID.class);
			assertThatThrownBy(() -> jdbc.update("INSERT INTO plan_feature (plan_id, feature_key) VALUES (?, 'TELEPATHY')", version))
				.isInstanceOf(DataAccessException.class);
			status.setRollbackOnly();
		});
	}

	@Test
	void aPlanVersionCanNeverBeChangedOnlyAddedAndARowCannotBeAddedToOneThatIsInUse() {
		UUID team = plan("TEAM");
		assertThatThrownBy(() -> jdbc.update("UPDATE plan SET name = 'Team XL' WHERE id = ?", team))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM plan WHERE id = ?", team))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.update("UPDATE plan_limit SET value = 1000 WHERE plan_id = ? AND limit_key = 'ACTIVE_MEMBERS'", team))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM plan_limit WHERE plan_id = ?", team))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM plan_feature WHERE plan_id = ?", team))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.update("UPDATE plan_feature SET feature_key = 'REPORTS' WHERE plan_id = ? "
				+ "AND feature_key = 'BENCHMARKING'", team)).isInstanceOf(DataAccessException.class);
		assertThat(limitsOf("TEAM")).containsEntry("ACTIVE_MEMBERS", 100);

		UUID company = newCompany();
		insertActive(company, "STARTER");
		UUID starter = plan("STARTER");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO plan_feature (plan_id, feature_key) VALUES (?, 'BENCHMARKING')", starter))
			.as("a feature added to a version in use would change existing subscriptions")
			.isInstanceOf(DataAccessException.class).hasMessageContaining("in use");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO plan_limit (plan_id, limit_key, value) VALUES (?, 'TEAMS', 1)", starter))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	void aLimitOrFeatureCannotBeAddedToAVersionInUseEvenForAKeyItLacks() {
		// One transaction per statement: PostgreSQL refuses everything after a failed statement in the same one.
		for (String insert : List.of("INSERT INTO plan_limit (plan_id, limit_key, value) VALUES (?, 'TEAMS', 99)",
				"INSERT INTO plan_feature (plan_id, feature_key) VALUES (?, 'BENCHMARKING')")) {
			UUID company = newCompany();
			tx.executeWithoutResult(status -> {
				UUID v2 = jdbc.queryForObject(
						"INSERT INTO plan (code, version, name, created_at) VALUES ('STARTER', 2, 'Starter', now()) RETURNING id",
						UUID.class);
				jdbc.update("INSERT INTO plan_limit (plan_id, limit_key, value) VALUES (?, 'ACTIVE_MEMBERS', 20)", v2);
				insertSubscription(company, v2, "ACTIVE", null, null, null);
				assertThatThrownBy(() -> jdbc.update(insert, v2)).as(insert)
					.isInstanceOf(DataAccessException.class).hasMessageContaining("in use");
				status.setRollbackOnly();
			});
		}
	}

	@Test
	void aNewVersionIsAddedWithItsOwnRowsAndAnExistingSubscriptionKeepsItsVersion() {
		UUID company = newCompany();
		insertActive(company, "STARTER");
		UUID before = jdbc.queryForObject("SELECT plan_id FROM company_subscription WHERE company_id = ?", UUID.class, company);

		tx.executeWithoutResult(status -> {
			UUID v2 = jdbc.queryForObject(
					"INSERT INTO plan (code, version, name, created_at) VALUES ('STARTER', 2, 'Starter', now()) RETURNING id",
					UUID.class);
			assertThatCode(() -> {
				jdbc.update("INSERT INTO plan_limit (plan_id, limit_key, value) VALUES (?, 'ACTIVE_MEMBERS', 20)", v2);
				jdbc.update("INSERT INTO plan_feature (plan_id, feature_key) VALUES (?, 'CORE_SCORING')", v2);
			}).doesNotThrowAnyException();
			assertThat(jdbc.queryForObject("SELECT plan_id FROM company_subscription WHERE company_id = ?", UUID.class, company))
				.as("the subscription keeps version 1").isEqualTo(before);
			assertThat(jdbc.queryForObject("SELECT p.version FROM plan p WHERE p.id = ?", Integer.class, before)).isEqualTo(1);
			status.setRollbackOnly();
		});
		assertThat(count("SELECT count(*) FROM plan WHERE code = 'STARTER'")).as("the test left no version behind").isEqualTo(1);
	}

	// ---- the subscription

	@Test
	void aTrialSubscriptionNeedsTheTrialPlanAndTimestampsThatAreExactlyFourteenPlusFourteenDays() {
		UUID company = newCompany();
		Instant ends = plusHours(START, TRIAL_HOURS);
		Instant readOnlyEnds = plusHours(ends, TRIAL_HOURS);

		assertThatCode(() -> insertTrialing(company)).doesNotThrowAnyException();
		assertThat(stateOf(company)).isEqualTo("TRIALING");

		UUID other = newCompany();
		assertThatThrownBy(() -> insertSubscription(other, plan("TRIAL"), "TRIALING", START, plusHours(ends, 1), readOnlyEnds))
			.as("the trial lasts exactly 14 days").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(other, plan("TRIAL"), "TRIALING", START, plusHours(START, TRIAL_HOURS - 1),
				plusHours(START, 2 * TRIAL_HOURS - 1))).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(other, plan("TRIAL"), "TRIALING", START, ends, plusHours(readOnlyEnds, 1)))
			.as("the read-only window lasts exactly 14 days").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(other, plan("TRIAL"), "TRIALING", START, ends, null))
			.as("the three timestamps come together").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(other, plan("TRIAL"), "TRIALING", null, null, null))
			.as("a trial has timestamps").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(other, null, "TRIALING", START, ends, readOnlyEnds))
			.as("a trial has a plan").isInstanceOf(DataAccessException.class);
	}

	@Test
	void theTrialLengthIsTheSameInEveryTimeZoneEvenAcrossADaylightSavingChange() {
		// A calendar interval would give 14 days of 23 or 25 hours across the change; the database counts hours.
		Instant start = Instant.parse("2026-03-01T12:00:00Z");
		Instant ends = plusHours(start, TRIAL_HOURS);
		Instant readOnlyEnds = plusHours(ends, TRIAL_HOURS);
		for (String zone : List.of("America/New_York", "Europe/London", "Pacific/Auckland")) {
			UUID company = newCompany();
			tx.executeWithoutResult(status -> {
				jdbc.execute("SET LOCAL TIME ZONE '" + zone + "'");
				assertThatCode(() -> insertSubscription(company, plan("TRIAL"), "TRIALING", start, ends, readOnlyEnds))
					.as(zone).doesNotThrowAnyException();
			});
		}
	}

	@Test
	void noPlanHasNoPlanAndNoTrialAndAnActiveSubscriptionHasAPaidPlan() {
		UUID company = newCompany();
		assertThatThrownBy(() -> insertSubscription(company, plan("STARTER"), "NO_PLAN", null, null, null))
			.as("NO_PLAN has no plan").isInstanceOf(DataAccessException.class);
		Instant ends = plusHours(START, TRIAL_HOURS);
		assertThatThrownBy(() -> insertSubscription(company, null, "NO_PLAN", START, ends, plusHours(ends, TRIAL_HOURS)))
			.as("NO_PLAN has no trial").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(company, null, "ACTIVE", null, null, null))
			.as("ACTIVE has a plan").isInstanceOf(DataAccessException.class);
		assertThatCode(() -> insertNoPlan(company)).doesNotThrowAnyException();
	}

	@Test
	void thePlanMustFitTheState() {
		UUID company = newCompany();
		Instant ends = plusHours(START, TRIAL_HOURS);
		Instant readOnlyEnds = plusHours(ends, TRIAL_HOURS);
		assertThatThrownBy(() -> insertSubscription(company, plan("STARTER"), "TRIALING", START, ends, readOnlyEnds))
			.as("a trial uses the Trial plan").isInstanceOf(DataAccessException.class).hasMessageContaining("Trial plan");
		assertThatThrownBy(() -> insertSubscription(company, plan("TEAM"), "EXPIRED_READ_ONLY", START, ends, readOnlyEnds))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(company, plan("TEAM"), "EXPIRED_LOCKED", START, ends, readOnlyEnds))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSubscription(company, plan("TRIAL"), "ACTIVE", null, null, null))
			.as("ACTIVE is a paid plan").isInstanceOf(DataAccessException.class).hasMessageContaining("Starter or Team");
		assertThatCode(() -> insertActive(company, "TEAM")).doesNotThrowAnyException();
		assertThatThrownBy(() -> jdbc.update("UPDATE company_subscription SET plan_id = ? WHERE company_id = ?", plan("TRIAL"), company))
			.as("a later update is checked too").isInstanceOf(DataAccessException.class);
	}

	@Test
	void aStateOutsideTheMvpListIsRefused() {
		for (String state : List.of("PAST_DUE", "CANCELED", "trialing", "")) {
			UUID company = newCompany();
			assertThatThrownBy(() -> insertSubscription(company, plan("TEAM"), state, null, null, null)).as(state)
				.isInstanceOf(DataAccessException.class);
		}
	}

	@Test
	void aCompanyHasOneSubscriptionAndEveryReferenceMustExist() {
		UUID company = newCompany();
		insertNoPlan(company);
		assertThatThrownBy(() -> insertNoPlan(company)).as("one subscription per company")
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertActive(company, "TEAM")).as("even in another state")
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNoPlan(UUID.randomUUID())).as("the company exists")
			.isInstanceOf(DataAccessException.class);
		UUID other = newCompany();
		assertThatThrownBy(() -> insertSubscription(other, UUID.randomUUID(), "ACTIVE", null, null, null))
			.as("the plan exists").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE company_subscription SET company_id = ? WHERE company_id = ?", other, company))
			.as("a subscription never moves to another company").isInstanceOf(DataAccessException.class);
	}

	// ---- the state machine

	private static final List<String> STATES = List.of("TRIALING", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED", "ACTIVE", "NO_PLAN");

	/** A subscription in the given state, built with the fields the state needs. */
	private UUID subscriptionIn(String state) {
		UUID company = newCompany();
		Instant ends = plusHours(START, TRIAL_HOURS);
		Instant readOnlyEnds = plusHours(ends, TRIAL_HOURS);
		switch (state) {
			case "NO_PLAN" -> insertNoPlan(company);
			case "ACTIVE" -> insertActive(company, "STARTER");
			default -> insertSubscription(company, plan("TRIAL"), state, START, ends, readOnlyEnds);
		}
		return company;
	}

	/** The columns that must change together with the state, as an UPDATE's SET list and its arguments. */
	private void changeState(UUID company, String to) {
		Instant ends = plusHours(START, TRIAL_HOURS);
		Instant readOnlyEnds = plusHours(ends, TRIAL_HOURS);
		switch (to) {
			case "NO_PLAN" -> jdbc.update("UPDATE company_subscription SET state = 'NO_PLAN', plan_id = NULL, "
					+ "trial_started_at = NULL, trial_ends_at = NULL, read_only_ends_at = NULL WHERE company_id = ?", company);
			case "ACTIVE" -> jdbc.update("UPDATE company_subscription SET state = 'ACTIVE', "
					+ "plan_id = CASE WHEN state = 'ACTIVE' THEN plan_id ELSE ? END WHERE company_id = ?", plan("TEAM"), company);
			default -> jdbc.update("UPDATE company_subscription SET state = ?, plan_id = ?, trial_started_at = ?, "
					+ "trial_ends_at = ?, read_only_ends_at = ? WHERE company_id = ?", to, plan("TRIAL"), ts(START), ts(ends),
					ts(readOnlyEnds), company);
		}
	}

	private static boolean allowed(String from, String to) {
		if (from.equals(to)) {
			return true;
		}
		return (from.equals("TRIALING") && to.equals("EXPIRED_READ_ONLY"))
				|| (from.equals("EXPIRED_READ_ONLY") && to.equals("EXPIRED_LOCKED"))
				|| (to.equals("ACTIVE") && !from.equals("ACTIVE"));
	}

	@Test
	void everyPairOfStatesIsAllowedOrRefusedExactlyAsTheSpecSays() {
		List<String> wrong = new ArrayList<>();
		for (String from : STATES) {
			for (String to : STATES) {
				UUID company = subscriptionIn(from);
				boolean accepted;
				try {
					changeState(company, to);
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
	void aPlanChangeWhileActiveIsAllowedOnlyInsideTheOperatorScriptAndAnOperatorCanLeaveEveryStateForActive() {
		UUID company = subscriptionIn("ACTIVE");
		assertThatThrownBy(() -> jdbc.update("UPDATE company_subscription SET plan_id = ? WHERE company_id = ?", plan("TEAM"), company))
			.as("without the operator marker").isInstanceOf(DataAccessException.class)
			.hasMessageContaining("operator plan script");
		assertThatCode(() -> tx.executeWithoutResult(status -> {
			jdbc.queryForObject("SELECT set_config('app.operator_plan_tx', 'yes', true)", String.class);
			jdbc.update("UPDATE company_subscription SET plan_id = ? WHERE company_id = ?", plan("TEAM"), company);
		})).as("with it").doesNotThrowAnyException();
		assertThat(jdbc.queryForObject("SELECT plan_id FROM company_subscription WHERE company_id = ?", UUID.class, company))
			.isEqualTo(plan("TEAM"));
		for (String from : List.of("TRIALING", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED", "NO_PLAN")) {
			UUID c = subscriptionIn(from);
			assertThatCode(() -> changeState(c, "ACTIVE")).as(from).doesNotThrowAnyException();
		}
	}

	@Test
	void theTrialDatesNeverChangeOnceSetNotEvenWithTheState() {
		for (String state : List.of("TRIALING", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED")) {
			UUID company = subscriptionIn(state);
			Instant later = START.plusSeconds(3600);
			assertThatThrownBy(() -> jdbc.update("UPDATE company_subscription SET trial_started_at = ?, trial_ends_at = ?, "
					+ "read_only_ends_at = ? WHERE company_id = ?", ts(later), ts(plusHours(later, TRIAL_HOURS)),
					ts(plusHours(later, 2 * TRIAL_HOURS)), company))
				.as(state).isInstanceOf(DataAccessException.class).hasMessageContaining("trial dates");
			assertThatThrownBy(() -> jdbc.update("UPDATE company_subscription SET trial_ends_at = ? WHERE company_id = ?",
					ts(plusHours(START, 1)), company)).as(state + ", one date").isInstanceOf(DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT trial_started_at FROM company_subscription WHERE company_id = ?",
					Timestamp.class, company)).as(state).isEqualTo(ts(START));
		}
	}

	@Test
	void theTrialDatesStayWhenAnOperatorMovesTheCompanyToActiveAndATimeTransitionKeepsThePlan() {
		UUID locked = subscriptionIn("EXPIRED_LOCKED");
		assertThatCode(() -> changeState(locked, "ACTIVE")).doesNotThrowAnyException();
		assertThat(jdbc.queryForObject("SELECT trial_started_at FROM company_subscription WHERE company_id = ?",
				Timestamp.class, locked)).isEqualTo(ts(START));

		UUID trial = subscriptionIn("TRIALING");
		assertThatThrownBy(() -> jdbc.update("UPDATE company_subscription SET state = 'EXPIRED_READ_ONLY', plan_id = ? "
				+ "WHERE company_id = ?", plan("TEAM"), trial)).isInstanceOf(DataAccessException.class);
		assertThatCode(() -> jdbc.update("UPDATE company_subscription SET state = 'EXPIRED_READ_ONLY' WHERE company_id = ?",
				trial)).doesNotThrowAnyException();
	}

	// ---- the trial grant

	@Test
	void aUserHasOneTrialGrantAndItReferencesNoCompany() {
		UUID user = newUser();
		jdbc.update("INSERT INTO user_trial_grant (user_id, company_id, granted_at) VALUES (?, ?, now())", user, UUID.randomUUID());
		assertThat(count("SELECT count(*) FROM user_trial_grant WHERE user_id = ?", user))
			.as("a company that is later purged leaves the grant in place").isEqualTo(1);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO user_trial_grant (user_id, company_id, granted_at) VALUES (?, ?, now())",
				user, UUID.randomUUID())).as("one grant per user").isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO user_trial_grant (user_id, company_id, granted_at) VALUES (?, ?, now())",
				UUID.randomUUID(), UUID.randomUUID())).as("the user exists").isInstanceOf(DataAccessException.class);
	}

	@Test
	void simultaneousFirstCompanyGrantsForOneUserAdmitExactlyOne() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID user = newUser();
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				tasks.add(() -> tx.execute(status -> jdbc.update("INSERT INTO user_trial_grant (user_id, company_id, granted_at) "
						+ "VALUES (?, ?, now()) ON CONFLICT DO NOTHING", user, UUID.randomUUID())));
			}

			List<Integer> inserted = Parallel.runAll(tasks);

			assertThat(inserted.stream().mapToInt(Integer::intValue).sum()).as("round %d: one Trial", round).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM user_trial_grant WHERE user_id = ?", user)).isEqualTo(1);
		}
	}

}
