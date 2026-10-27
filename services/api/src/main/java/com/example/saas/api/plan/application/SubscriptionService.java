package com.example.saas.api.plan.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.plan.persistence.CompanySubscription;
import com.example.saas.api.plan.persistence.CompanySubscriptionRepository;
import com.example.saas.api.plan.persistence.Plan;
import com.example.saas.api.plan.persistence.PlanRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates a company's subscription inside the company-creation transaction (AC-PLAN-09, D-45). */
@Service
public class SubscriptionService {

	static final String TRIAL = "TRIAL";

	private final CompanySubscriptionRepository subscriptions;

	private final PlanRepository plans;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	SubscriptionService(CompanySubscriptionRepository subscriptions, PlanRepository plans, JdbcClient jdbc,
			AuditLog audit) {
		this.subscriptions = subscriptions;
		this.plans = plans;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	/**
	 * Gives the new company its subscription: a Trial if this is the user's first company, otherwise NO_PLAN (AC-PLAN-15).
	 * The company row must already be flushed. The grant row's primary key decides a race: of two simultaneous first
	 * companies by one user the second insert waits for the first transaction, then finds the row and gets NO_PLAN, or,
	 * if the first rolled back, takes the slot itself (AC-PLAN-25). The start instant and both end instants are computed
	 * here from {@code now}; a failed company creation rolls all of it back (AC-PLAN-09).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public SubscriptionState startFor(UUID userId, UUID companyId, Instant now) {
		int granted = jdbc.sql("""
				INSERT INTO user_trial_grant (user_id, company_id, granted_at) VALUES (:user, :company, :now)
				ON CONFLICT (user_id) DO NOTHING
				""")
			.param("user", userId)
			.param("company", companyId)
			.param("now", Timestamp.from(now))
			.update();
		if (granted == 0) {
			subscriptions.saveAndFlush(CompanySubscription.noPlan(companyId, now));
			return SubscriptionState.NO_PLAN;
		}
		Plan trial = plans.findLatestByCode(TRIAL)
			.orElseThrow(() -> new IllegalStateException("The Trial plan is missing from the catalog"));
		subscriptions.saveAndFlush(CompanySubscription.trial(companyId, trial.getId(), now));
		audit.recordChange("plan.subscription.transitioned", AuditLog.Actor.system, null, "company_subscription",
				companyId, companyId, "success", "trial_started", SubscriptionState.NO_PLAN.name(),
				SubscriptionState.TRIALING.name());
		return SubscriptionState.TRIALING;
	}

}
