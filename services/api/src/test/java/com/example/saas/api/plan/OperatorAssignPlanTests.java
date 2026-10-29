package com.example.saas.api.plan;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.plan.application.EntitlementService;
import com.example.saas.api.plan.application.SubscriptionSweep;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.RunbookSql;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.MemberLimitReachedException;
import com.example.saas.api.tenant.application.MembershipService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-PLAN-21 and plan Q-15, Q-16, Q-18: the operator's plan assignment, run from the runbook's own SQL against
 * PostgreSQL. Every safety condition of the script has a test that fails when the condition is removed (the mutation
 * checks are recorded in the traceability file).
 */
class OperatorAssignPlanTests extends PlanTestBase {

	private static final Path ASSIGN = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"operator-assign-plan.sql");

	private static final int ASSIGN_STATEMENTS = 17;

	private static final Duration WINDOW = Duration.ofHours(336);

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private EntitlementService entitlements;

	@Autowired
	private SubscriptionSweep sweep;

	@Autowired
	private MembershipService memberships;

	private record Company(UUID id, UUID owner) {
	}

	private Company company() {
		UUID owner = newUser();
		return new Company(createCompany(owner).id(), owner);
	}

	private Map<String, String> variables(UUID companyId, String plan, String ticket) {
		return Map.of("company", companyId.toString(), "plan", plan, "ticket", ticket);
	}

	private void assign(UUID companyId, String plan) throws IOException {
		runStatements(variables(companyId, plan, "BILL-1"));
	}

	private void runStatements(Map<String, String> variables) throws IOException {
		List<String> statements = RunbookSql.statements(ASSIGN, variables);
		assertThat(statements).as("the statements of %s", ASSIGN.getFileName()).hasSize(ASSIGN_STATEMENTS);
		tx.executeWithoutResult(s -> statements.forEach(jdbc::execute));
	}

	/** Replaces the company's subscription with a trial that started {@code startedAgo} ago. */
	private void giveTrialStartedAgo(UUID companyId, Duration startedAgo) {
		Instant start = clock.instant().minus(startedAgo);
		tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM company_subscription WHERE company_id = ?", companyId);
			jdbc.update("INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, "
					+ "read_only_ends_at, state_changed_at, created_at, updated_at) VALUES (?, (SELECT id FROM plan "
					+ "WHERE code = 'TRIAL' AND version = 1), 'TRIALING', ?, ?, ?, now(), now(), now())", companyId,
					Timestamp.from(start), Timestamp.from(start.plus(WINDOW)), Timestamp.from(start.plus(WINDOW).plus(WINDOW)));
		});
	}

	private void giveNoPlan(UUID companyId) {
		tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM company_subscription WHERE company_id = ?", companyId);
			jdbc.update("INSERT INTO company_subscription (company_id, state, state_changed_at, created_at, updated_at) "
					+ "VALUES (?, 'NO_PLAN', now(), now(), now())", companyId);
		});
	}

	/** What the sweep records: the stored state moves along the time transitions. */
	private void record(UUID companyId, String state) {
		jdbc.update("UPDATE company_subscription SET state = ? WHERE company_id = ?", state, companyId);
	}

	private UUID member(UUID companyId, String status) {
		UUID user = newUser();
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, 'AGENT', ?, now(), now()) RETURNING id", UUID.class, companyId, user, status);
	}

	private String plan(UUID companyId) {
		return jdbc.queryForObject("SELECT coalesce(p.code || ':' || p.version, 'none') FROM company_subscription s "
				+ "LEFT JOIN plan p ON p.id = s.plan_id WHERE s.company_id = ?", String.class, companyId);
	}

	private List<Map<String, Object>> audit(UUID companyId, String eventType) {
		return jdbc.queryForList("SELECT actor_type, target_type, target_id, outcome, before_value, after_value, reason_code "
				+ "FROM audit_event WHERE company_id = ? AND event_type = ? AND actor_type = 'operator'", companyId, eventType);
	}

	/** Everything the script may change for one company, as one string. */
	private String snapshot(UUID companyId) {
		return String.join("|",
				jdbc.queryForList("SELECT state || ':' || coalesce(plan_id::text, '-') || ':' || version FROM company_subscription "
						+ "WHERE company_id = ?", String.class, companyId).toString(),
				String.valueOf(count("SELECT count(*) FROM audit_event WHERE company_id = ?", companyId)),
				String.valueOf(count("SELECT count(*) FROM membership WHERE company_id = ?", companyId)));
	}

	/** Refused by one of the script's own guards (a division by zero), not by a later statement or a constraint. */
	private void assertGuardStops(Map<String, String> variables, String why) {
		assertThatThrownBy(() -> runStatements(variables)).as(why)
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("division by zero");
	}

	private void assertRefused(UUID companyId, Map<String, String> variables, String why) {
		String before = snapshot(companyId);
		assertGuardStops(variables, why);
		assertThat(snapshot(companyId)).as("%s: nothing changed", why).isEqualTo(before);
	}

	@Test
	void everyRecordedStateBecomesActiveOnTheChosenPlanAndIsAudited() throws Exception {
		for (String start : List.of("NO_PLAN", "TRIALING", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED")) {
			Company company = company();
			switch (start) {
				case "NO_PLAN" -> giveNoPlan(company.id());
				case "EXPIRED_READ_ONLY" -> record(company.id(), start);
				case "EXPIRED_LOCKED" -> {
					record(company.id(), "EXPIRED_READ_ONLY");
					record(company.id(), start);
				}
				default -> {
				}
			}
			assertThat(stateOf(company.id())).isEqualTo(start);

			assign(company.id(), "TEAM");

			assertThat(stateOf(company.id())).as("from %s", start).isEqualTo("ACTIVE");
			assertThat(plan(company.id())).isEqualTo("TEAM:1");
			List<Map<String, Object>> transitioned = audit(company.id(), "plan.subscription.transitioned");
			assertThat(transitioned).hasSize(1);
			Map<String, Object> event = transitioned.get(0);
			assertThat(event).containsEntry("outcome", "success")
				.containsEntry("target_type", "company_subscription")
				.containsEntry("target_id", company.id())
				.containsEntry("before_value", start)
				.containsEntry("after_value", "ACTIVE");
			assertThat((String) event.get("reason_code")).startsWith("operator_assigned ticket=BILL-1 db_user=");
			List<Map<String, Object>> changed = audit(company.id(), "plan.entitlement.changed");
			assertThat(changed).hasSize(1);
			assertThat(changed.get(0)).containsEntry("before_value", start.equals("NO_PLAN") ? null : "TRIAL")
				.containsEntry("after_value", "TEAM");
			assertThat((String) changed.get(0).get("reason_code")).startsWith(
					"from_version=" + (start.equals("NO_PLAN") ? "none" : "1") + " to_version=1 ticket=BILL-1 db_user=");
		}
	}

	@Test
	void theAssignmentIsEffectiveAtOnceEvenWhenTheTrialHadEndedWithoutTheSweepRecordingIt() throws Exception {
		Company company = company();
		giveTrialStartedAgo(company.id(), WINDOW.multipliedBy(2).plusDays(1));
		assertThat(stateOf(company.id())).isEqualTo("TRIALING");
		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.EXPIRED_LOCKED);

		assign(company.id(), "STARTER");

		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.ACTIVE);
		assertThat(entitlements.describe(company.id()).plan().code()).isEqualTo("STARTER");
		assertThat(audit(company.id(), "plan.subscription.transitioned").get(0)).containsEntry("before_value",
				"EXPIRED_LOCKED");
	}

	/** The system's own transition events of one company, ordered by reason (both hops of one run carry the same instant), as the sweep writes them. */
	private List<Map<String, Object>> systemTransitions(UUID companyId) {
		return jdbc.queryForList("SELECT event_type, actor_type, target_type, target_id, outcome, before_value, after_value, "
				+ "reason_code FROM audit_event WHERE company_id = ? AND actor_type = 'system' "
				+ "AND reason_code IN ('trial_expired', 'read_only_ended') ORDER BY reason_code", companyId);
	}

	@Test
	void theTimeTransitionsThatAreDueAreRecordedBeforeTheAssignmentAsTheSweepWritesThem() throws Exception {
		Company readOnly = company();
		giveTrialStartedAgo(readOnly.id(), WINDOW.plusDays(1));
		Company locked = company();
		giveTrialStartedAgo(locked.id(), WINDOW.multipliedBy(2).plusDays(1));
		Company running = company();
		giveTrialStartedAgo(running.id(), Duration.ofDays(1));
		Company recorded = company();
		giveTrialStartedAgo(recorded.id(), WINDOW.multipliedBy(2).plusDays(1));
		record(recorded.id(), "EXPIRED_READ_ONLY");

		for (Company company : List.of(readOnly, locked, running, recorded)) {
			assign(company.id(), "TEAM");
		}

		assertThat(systemTransitions(readOnly.id())).extracting(e -> e.get("reason_code"), e -> e.get("before_value"),
				e -> e.get("after_value")).containsExactly(
						org.assertj.core.groups.Tuple.tuple("trial_expired", "TRIALING", "EXPIRED_READ_ONLY"));
		assertThat(audit(readOnly.id(), "plan.subscription.transitioned").get(0)).containsEntry("before_value",
				"EXPIRED_READ_ONLY");
		assertThat(systemTransitions(locked.id())).extracting(e -> e.get("reason_code"), e -> e.get("before_value"),
				e -> e.get("after_value")).containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("trial_expired", "TRIALING", "EXPIRED_READ_ONLY"),
						org.assertj.core.groups.Tuple.tuple("read_only_ended", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED"));
		assertThat(audit(locked.id(), "plan.subscription.transitioned").get(0)).containsEntry("before_value",
				"EXPIRED_LOCKED");
		assertThat(systemTransitions(running.id())).as("nothing is due").isEmpty();
		assertThat(audit(running.id(), "plan.subscription.transitioned").get(0)).containsEntry("before_value", "TRIALING");
		assertThat(systemTransitions(recorded.id())).extracting(e -> e.get("reason_code")).as("only the missing hop")
			.containsExactly("read_only_ended");
	}

	@Test
	void anActiveCompanyGetsNoTimeTransitionAndASecondRunWritesNothing() throws Exception {
		Company company = company();
		giveTrialStartedAgo(company.id(), WINDOW.multipliedBy(2).plusDays(1));
		assign(company.id(), "TEAM");
		long audited = count("SELECT count(*) FROM audit_event WHERE company_id = ?", company.id());

		assign(company.id(), "TEAM");

		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ?", company.id())).isEqualTo(audited);
		assertThat(systemTransitions(company.id())).hasSize(2);
	}

	@Test
	void theScriptWritesTheSameEventsAsTheSweepForTheSameSituation() throws Exception {
		Company bySweep = company();
		giveTrialStartedAgo(bySweep.id(), WINDOW.multipliedBy(2).plusDays(1));
		Company byScript = company();
		giveTrialStartedAgo(byScript.id(), WINDOW.multipliedBy(2).plusDays(1));

		assign(byScript.id(), "TEAM");
		sweep.runOnce();

		List<Map<String, Object>> expected = systemTransitions(bySweep.id());
		assertThat(expected).hasSize(2);
		assertThat(systemTransitions(byScript.id())).usingRecursiveComparison().ignoringFields("target_id")
			.isEqualTo(expected);
		assertThat(stateOf(bySweep.id())).isEqualTo("EXPIRED_LOCKED");
	}

	@Test
	void changingTheActivePlanIsAnOperatorChange() throws Exception {
		Company company = company();
		assign(company.id(), "STARTER");

		runStatements(variables(company.id(), "TEAM", "BILL-2"));

		assertThat(plan(company.id())).isEqualTo("TEAM:1");
		List<Map<String, Object>> transitioned = audit(company.id(), "plan.subscription.transitioned");
		assertThat(transitioned).hasSize(2);
		assertThat(transitioned).anySatisfy(e -> {
			assertThat(e).containsEntry("before_value", "ACTIVE").containsEntry("after_value", "ACTIVE");
			assertThat((String) e.get("reason_code")).startsWith("operator_changed ticket=BILL-2 db_user=");
		});
		assertThat(audit(company.id(), "plan.entitlement.changed")).anySatisfy(e -> {
			assertThat(e).containsEntry("before_value", "STARTER").containsEntry("after_value", "TEAM");
			assertThat((String) e.get("reason_code")).startsWith("from_version=1 to_version=1 ticket=BILL-2 db_user=");
		});
		assertThat(entitlements.describe(company.id()).limits()).anySatisfy(l -> {
			assertThat(l.key().name()).isEqualTo("ACTIVE_MEMBERS");
			assertThat(l.value()).isEqualTo(100);
		});
	}

	@Test
	void aSecondRunWithTheSamePlanChangesNothingAndWritesNoSecondEvent() throws Exception {
		Company company = company();
		assign(company.id(), "TEAM");
		String after = snapshot(company.id());

		runStatements(variables(company.id(), "TEAM", "BILL-9"));

		assertThat(snapshot(company.id())).isEqualTo(after);
		assertThat(audit(company.id(), "plan.subscription.transitioned")).hasSize(1);
		assertThat(audit(company.id(), "plan.entitlement.changed")).hasSize(1);
	}

	@Test
	void theNewestVersionOfThePlanIsUsedAndANewerVersionIsAChange() throws Exception {
		Company company = company();
		tx.executeWithoutResult(s -> {
			try {
				runStatements(variables(company.id(), "STARTER", "BILL-1"));
				UUID v1 = jdbc.queryForObject("SELECT plan_id FROM company_subscription WHERE company_id = ?", UUID.class,
						company.id());
				UUID v2 = jdbc.queryForObject("INSERT INTO plan (code, version, name, created_at) VALUES ('STARTER', 2, "
						+ "'Starter', now()) RETURNING id", UUID.class);
				assertThat(v2).isNotEqualTo(v1);

				runStatements(variables(company.id(), "STARTER", "BILL-2"));

				assertThat(plan(company.id())).isEqualTo("STARTER:2");
				assertThat(audit(company.id(), "plan.subscription.transitioned")).anySatisfy(e -> assertThat(
						(String) e.get("reason_code")).startsWith("operator_changed ticket=BILL-2 "));
				assertThat(audit(company.id(), "plan.entitlement.changed")).anySatisfy(e -> {
					assertThat(e).containsEntry("before_value", "STARTER").containsEntry("after_value", "STARTER");
					assertThat((String) e.get("reason_code")).startsWith("from_version=1 to_version=2 ticket=BILL-2 ");
				});
			}
			catch (IOException e) {
				throw new IllegalStateException(e);
			}
			finally {
				// The catalog is immutable, so the extra version must never be committed.
				s.setRollbackOnly();
			}
		});

		assertThat(count("SELECT count(*) FROM plan WHERE code = 'STARTER' AND version = 2")).isZero();
	}

	@Test
	void aDowngradeBelowCurrentUsageRemovesNobodyAndOnlyBlocksNewActiveMembers() throws Exception {
		Company company = company();
		assign(company.id(), "TEAM");
		List<UUID> others = new java.util.ArrayList<>();
		for (int i = 0; i < 19; i++) {
			others.add(member(company.id(), "ACTIVE"));
		}
		UUID waiting = member(company.id(), "SUSPENDED");
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND status = 'ACTIVE'", company.id())).isEqualTo(20);

		assign(company.id(), "STARTER");

		assertThat(plan(company.id())).isEqualTo("STARTER:1");
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND status = 'ACTIVE'", company.id()))
			.as("nobody is removed or suspended").isEqualTo(20);
		assertThatThrownBy(() -> memberships.reactivate(session(company.owner()), company.id(), waiting))
			.isInstanceOf(MemberLimitReachedException.class);

		// Below the limit again: the reactivation is allowed.
		for (UUID seat : others.subList(0, 6)) {
			jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", seat);
		}
		memberships.reactivate(session(company.owner()), company.id(), waiting);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND status = 'ACTIVE'", company.id()))
			.isEqualTo(15);
	}

	@Test
	void otherCompaniesAreNotTouched() throws Exception {
		Company target = company();
		Company other = company();
		String before = snapshot(other.id());

		assign(target.id(), "TEAM");

		assertThat(snapshot(other.id())).isEqualTo(before);
		assertThat(plan(other.id())).isEqualTo("TRIAL:1");
	}

	@Test
	void theAuditRowsHoldNoAddressAndNoName() throws Exception {
		Company company = company();
		String email = jdbc.queryForObject("SELECT email_original FROM app_user WHERE id = ?", String.class, company.owner());

		assign(company.id(), "TEAM");

		List<String> reasons = jdbc.queryForList("SELECT reason_code FROM audit_event WHERE company_id = ? "
				+ "AND actor_type = 'operator'", String.class, company.id());
		assertThat(reasons).hasSize(2).allSatisfy(r -> {
			assertThat(r).doesNotContain(email).doesNotContain("Plan");
			assertThat(r).matches("[a-z_0-9= .A-Z-]+");
		});
	}

	@Test
	void aMalformedTicketIsRefused() {
		Company company = company();
		for (String ticket : List.of("", "has space", "semi;colon", "x".repeat(41), "q'uote")) {
			assertRefused(company.id(), variables(company.id(), "TEAM", ticket), "ticket '" + ticket + "'");
		}
	}

	@Test
	void onlyStarterAndTeamCanBeAssigned() {
		Company company = company();
		for (String plan : List.of("TRIAL", "team", "ENTERPRISE", "", "TEAM ")) {
			assertRefused(company.id(), variables(company.id(), plan, "BILL-1"), "plan '" + plan + "'");
		}
	}

	@Test
	void theCompanyIdMustBeALowerCaseUuid() {
		Company company = company();
		String upper = company.id().toString().toUpperCase();
		for (String id : List.of(upper, "not-a-uuid", "", company.id() + " ")) {
			String before = snapshot(company.id());
			assertGuardStops(variables(company.id(), "TEAM", "BILL-1").entrySet().stream()
				.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
						e -> e.getKey().equals("company") ? id : e.getValue())), "company '" + id + "'");
			assertThat(snapshot(company.id())).isEqualTo(before);
		}
	}

	@Test
	void anUnknownCompanyIsRefused() {
		Company company = company();
		assertRefused(company.id(), variables(UUID.randomUUID(), "TEAM", "BILL-1"), "unknown company");
	}

	@Test
	void aCompanyWithoutASubscriptionRowIsRefusedAndNotRepaired() {
		Company company = company();
		tx.executeWithoutResult(s -> jdbc.update("DELETE FROM company_subscription WHERE company_id = ?", company.id()));

		assertRefused(company.id(), variables(company.id(), "TEAM", "BILL-1"), "no subscription row");
		assertThat(count("SELECT count(*) FROM company_subscription WHERE company_id = ?", company.id())).isZero();
	}

	@Test
	void runOutsideOneTransactionTheScriptStopsBeforeWritingAnything() throws Exception {
		Company company = company();
		List<String> statements = RunbookSql.statements(ASSIGN, variables(company.id(), "TEAM", "BILL-1"));
		String before = snapshot(company.id());

		// No transaction: every statement commits on its own, as psql does without --single-transaction.
		assertThatThrownBy(() -> statements.forEach(jdbc::execute)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("division by zero");

		assertThat(snapshot(company.id())).isEqualTo(before);
		assertThat(stateOf(company.id())).isEqualTo("TRIALING");
	}

	@Test
	void theScriptWaitsForTheCompanyLockAndThenReadsTheStateAsItThenIs() throws Exception {
		Company company = company();
		List<String> statements = RunbookSql.statements(ASSIGN, variables(company.id(), "TEAM", "BILL-1"));

		CompletableFuture<Void> script = new CompletableFuture<>();
		tx.executeWithoutResult(s -> {
			locks.lockCompany(company.id());
			CompletableFuture.runAsync(() -> {
				try {
					tx.executeWithoutResult(t -> statements.forEach(jdbc::execute));
					script.complete(null);
				}
				catch (RuntimeException e) {
					script.completeExceptionally(e);
				}
			});
			sleep(1500);
			assertThat(script.isDone()).as("the script waits for the company lock").isFalse();
			// What the sweep would record while the lock is held.
			record(company.id(), "EXPIRED_READ_ONLY");
		});

		script.get(20, TimeUnit.SECONDS);
		assertThat(stateOf(company.id())).isEqualTo("ACTIVE");
		assertThat(audit(company.id(), "plan.subscription.transitioned").get(0)).containsEntry("before_value",
				"EXPIRED_READ_ONLY");
	}

	@Test
	void theScriptGivesUpAfterFiveSecondsOfWaitingForTheCompanyLock() throws Exception {
		Company company = company();
		List<String> statements = RunbookSql.statements(ASSIGN, variables(company.id(), "TEAM", "BILL-1"));
		String before = snapshot(company.id());

		CompletableFuture<Void> script = new CompletableFuture<>();
		long[] waited = new long[1];
		tx.executeWithoutResult(s -> {
			locks.lockCompany(company.id());
			long start = System.nanoTime();
			CompletableFuture.runAsync(() -> {
				try {
					tx.executeWithoutResult(t -> statements.forEach(jdbc::execute));
					script.complete(null);
				}
				catch (RuntimeException e) {
					waited[0] = (System.nanoTime() - start) / 1_000_000;
					script.completeExceptionally(e);
				}
			});
			// Hold the lock well past the script's own 5 seconds; the script must give up by itself.
			sleep(7500);
			assertThat(script.isDone()).as("the script gave up while the lock was still held").isTrue();
		});

		assertThatThrownBy(() -> script.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(DataAccessException.class)
			.cause()
			.rootCause()
			.hasMessageContaining("lock timeout");
		assertThat(waited[0]).as("it waited about 5 seconds, not less and not for ever").isBetween(4500L, 7000L);
		assertThat(snapshot(company.id())).isEqualTo(before);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
