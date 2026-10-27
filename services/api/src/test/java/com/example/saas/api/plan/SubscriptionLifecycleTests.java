package com.example.saas.api.plan;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.plan.application.EntitlementService;
import com.example.saas.api.plan.application.SubscriptionService;
import com.example.saas.api.plan.application.SubscriptionSweep;
import com.example.saas.api.plan.domain.FeatureKey;
import com.example.saas.api.plan.domain.LimitKey;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.CompanyView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The subscription from creation to the sweep, against PostgreSQL: AC-PLAN-01, 09, 10, 11, 15, 16, 18 and 25 (plan
 * Q-06, Q-07, Q-20).
 */
class SubscriptionLifecycleTests extends PlanTestBase {

	private static final Duration WINDOW = Duration.ofHours(336);

	@Autowired
	private EntitlementService entitlements;

	@Autowired
	private SubscriptionService subscriptionService;

	@Autowired
	private SubscriptionSweep sweep;

	private Instant instant(String column, UUID companyId) {
		return jdbc.queryForObject("SELECT " + column + " FROM company_subscription WHERE company_id = ?",
				Timestamp.class, companyId)
			.toInstant();
	}

	/** A company and its Owner inserted directly: no subscription, so the test can start one itself. */
	private UUID bareCompany() {
		return tx.execute(s -> {
			UUID owner = newUser();
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Bare Co', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, owner);
			return id;
		});
	}

	// ---- creation

	@Test
	void theFirstCompanyOfAUserStartsATrialComputedOnTheServer() {
		UUID user = newUser();
		Instant before = clock.instant();

		CompanyView company = createCompany(user);

		assertThat(stateOf(company.id())).isEqualTo("TRIALING");
		assertThat(instant("trial_started_at", company.id())).isEqualTo(before);
		assertThat(instant("trial_ends_at", company.id())).isEqualTo(before.plus(WINDOW));
		assertThat(instant("read_only_ends_at", company.id())).isEqualTo(before.plus(WINDOW).plus(WINDOW));
		assertThat(jdbc.queryForObject("SELECT p.code FROM company_subscription s JOIN plan p ON p.id = s.plan_id "
				+ "WHERE s.company_id = ?", String.class, company.id())).isEqualTo("TRIAL");
		assertThat(transitions(company.id(), "trial_started")).isEqualTo(1);
		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.TRIALING);
	}

	@Test
	void aSecondCompanyOfTheSameUserHasNoPlanAndNoAuditedTransition() {
		UUID user = newUser();
		createCompany(user);

		CompanyView second = createCompany(user);

		assertThat(stateOf(second.id())).isEqualTo("NO_PLAN");
		assertThat(jdbc.queryForObject("SELECT plan_id FROM company_subscription WHERE company_id = ?", UUID.class,
				second.id()))
			.isNull();
		assertThat(transitions(second.id(), "trial_started")).isZero();
		assertThat(entitlements.state(second.id())).isEqualTo(SubscriptionState.NO_PLAN);
		assertThat(entitlements.limit(second.id(), LimitKey.ACTIVE_MEMBERS)).isEmpty();
		assertThat(entitlements.hasFeature(second.id(), FeatureKey.CORE_SCORING)).isFalse();
	}

	@Test
	void aFailedCompanyCreationLeavesNoSubscriptionAndNoGrant() {
		UUID user = newUser();
		UUID companyId = UUID.randomUUID();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			jdbc.update("INSERT INTO company (id, name, created_at, updated_at) VALUES (?, 'Doomed', now(), now())",
					companyId);
			subscriptionService.startFor(user, companyId, clock.instant());
			throw new IllegalStateException("the creation fails after the subscription was written");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("SELECT count(*) FROM company_subscription WHERE company_id = ?", companyId)).isZero();
		assertThat(count("SELECT count(*) FROM user_trial_grant WHERE user_id = ?", user)).isZero();
		// The slot was not used up: the user's next company still gets the Trial.
		assertThat(stateOf(createCompany(user).id())).isEqualTo("TRIALING");
	}

	@Test
	void eightSimultaneousFirstCompaniesOfOneUserYieldExactlyOneTrial() throws Exception {
		UUID user = newUser();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			ids.add(bareCompany());
		}
		List<Callable<SubscriptionState>> tasks = new ArrayList<>();
		for (UUID id : ids) {
			tasks.add(() -> tx.execute(s -> subscriptionService.startFor(user, id, clock.instant())));
		}

		List<SubscriptionState> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter(SubscriptionState.TRIALING::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter(SubscriptionState.NO_PLAN::equals).count()).isEqualTo(7);
		assertThat(count("SELECT count(*) FROM user_trial_grant WHERE user_id = ?", user)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM company_subscription WHERE state = 'TRIALING' AND company_id IN "
				+ "(SELECT company_id FROM user_trial_grant WHERE user_id = ?)", user)).isEqualTo(1);
	}

	// ---- evaluation

	@Test
	void aRequestAfterTheEndInstantSeesTheExpiredStateWithoutAnyJobHavingRun() {
		CompanyView company = createCompany(newUser());
		Instant trialEnd = instant("trial_ends_at", company.id());
		Instant readOnlyEnd = instant("read_only_ends_at", company.id());

		clock.set(trialEnd.minusMillis(1));
		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.TRIALING);
		clock.set(trialEnd);
		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.EXPIRED_READ_ONLY);
		clock.set(readOnlyEnd.minusMillis(1));
		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.EXPIRED_READ_ONLY);
		clock.set(readOnlyEnd);
		assertThat(entitlements.state(company.id())).isEqualTo(SubscriptionState.EXPIRED_LOCKED);

		// Reading wrote nothing.
		assertThat(stateOf(company.id())).isEqualTo("TRIALING");
		assertThat(transitions(company.id(), "trial_expired")).isZero();
	}

	@Test
	void theTrialPlanHasTheFullFeatureSetAndTheTrialLimits() {
		CompanyView company = createCompany(newUser());

		for (FeatureKey feature : FeatureKey.values()) {
			assertThat(entitlements.hasFeature(company.id(), feature)).as(feature.name()).isTrue();
		}
		assertThat(entitlements.limit(company.id(), LimitKey.ACTIVE_MEMBERS)).hasValue(15);
		assertThat(entitlements.limit(company.id(), LimitKey.TEAMS)).hasValue(3);
		assertThat(entitlements.limit(company.id(), LimitKey.ACTIVE_ASSESSMENTS)).hasValue(3);
	}

	// ---- sweep

	@Test
	void theSweepRecordsEachTimeTransitionOnceWithItsAuditEvent() {
		CompanyView company = createCompany(newUser());
		Instant trialEnd = instant("trial_ends_at", company.id());
		Instant readOnlyEnd = instant("read_only_ends_at", company.id());

		// The sweep is global and other tests leave companies behind, so each step is judged on this company only.
		clock.set(trialEnd.minusMillis(1));
		sweep.runOnce();
		assertThat(stateOf(company.id())).isEqualTo("TRIALING");

		clock.set(trialEnd);
		sweep.runOnce();
		assertThat(stateOf(company.id())).isEqualTo("EXPIRED_READ_ONLY");
		assertThat(transitions(company.id(), "trial_expired")).isEqualTo(1);

		// Repeating changes nothing and writes no second event (AC-PLAN-11).
		sweep.runOnce();
		assertThat(transitions(company.id(), "trial_expired")).isEqualTo(1);

		clock.set(readOnlyEnd);
		sweep.runOnce();
		assertThat(stateOf(company.id())).isEqualTo("EXPIRED_LOCKED");
		assertThat(transitions(company.id(), "read_only_ended")).isEqualTo(1);
		sweep.runOnce();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'plan.subscription.transitioned' "
				+ "AND company_id = ?", company.id())).isEqualTo(3);
	}

	@Test
	void aSweepThatWasDownForLongStepsThroughBothTransitions() {
		CompanyView company = createCompany(newUser());
		clock.set(instant("read_only_ends_at", company.id()).plusSeconds(1));

		sweep.runOnce();

		assertThat(stateOf(company.id())).isEqualTo("EXPIRED_LOCKED");
		assertThat(jdbc.queryForList("SELECT before_value || '>' || after_value FROM audit_event WHERE event_type = "
				+ "'plan.subscription.transitioned' AND company_id = ? AND reason_code <> 'trial_started'", String.class, company.id()))
			.containsExactlyInAnyOrder("TRIALING>EXPIRED_READ_ONLY", "EXPIRED_READ_ONLY>EXPIRED_LOCKED");
	}

	@Test
	void twoSweepsAtOnceRecordTheTransitionOnce() throws Exception {
		CompanyView company = createCompany(newUser());
		clock.set(instant("trial_ends_at", company.id()));
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			tasks.add(sweep::runOnce);
		}

		Parallel.runAll(tasks);

		assertThat(stateOf(company.id())).isEqualTo("EXPIRED_READ_ONLY");
		assertThat(transitions(company.id(), "trial_expired")).isEqualTo(1);
	}

	@Test
	void theSweepNeverTouchesActiveOrNoPlanSubscriptions() {
		UUID user = newUser();
		createCompany(user);
		CompanyView noPlan = createCompany(user);
		clock.advance(Duration.ofDays(400));

		sweep.runOnce();

		assertThat(stateOf(noPlan.id())).isEqualTo("NO_PLAN");
	}

}
