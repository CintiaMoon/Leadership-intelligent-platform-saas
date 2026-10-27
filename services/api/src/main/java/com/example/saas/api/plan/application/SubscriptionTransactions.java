package com.example.saas.api.plan.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.plan.domain.SubscriptionTransitions;
import com.example.saas.api.plan.domain.SubscriptionTransitions.Outcome;
import com.example.saas.api.plan.persistence.CompanySubscription;
import com.example.saas.api.plan.persistence.CompanySubscriptionRepository;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records, under the company lock, the transitions that time has already decided (Q-06). Correctness never depends on
 * it: decisions use the effective state. It steps one transition at a time (the table has no direct jump from TRIALING
 * to EXPIRED_LOCKED), each with its own audit event, and repeating it changes nothing (AC-PLAN-11).
 */
@Component
class SubscriptionTransactions {

	private final CompanyLocks companyLocks;

	private final CompanySubscriptionRepository subscriptions;

	private final AuditLog audit;

	private final Clock clock;

	SubscriptionTransactions(CompanyLocks companyLocks, CompanySubscriptionRepository subscriptions, AuditLog audit,
			Clock clock) {
		this.companyLocks = companyLocks;
		this.subscriptions = subscriptions;
		this.audit = audit;
		this.clock = clock;
	}

	/** @return how many transitions were recorded (0 if nothing was due or another run already did it) */
	@Transactional
	int recordDue(UUID companyId) {
		try {
			companyLocks.lockCompany(companyId);
		}
		catch (CompanyNotFoundException gone) {
			return 0;
		}
		CompanySubscription subscription = subscriptions.findById(companyId).orElse(null);
		if (subscription == null) {
			return 0;
		}
		Instant now = clock.instant();
		SubscriptionState target = subscription.effectiveAt(now);
		int recorded = 0;
		while (subscription.getState() != target) {
			SubscriptionState from = subscription.getState();
			SubscriptionState next = from == SubscriptionState.TRIALING ? SubscriptionState.EXPIRED_READ_ONLY
					: SubscriptionState.EXPIRED_LOCKED;
			if (!SubscriptionTransitions.isByTime(from, next)
					|| SubscriptionTransitions.check(from, next) != Outcome.APPLY) {
				throw new IllegalStateException("Unexpected subscription transition " + from + " to " + next);
			}
			subscription.recordState(next, now);
			subscriptions.saveAndFlush(subscription);
			audit.recordChange("plan.subscription.transitioned", AuditLog.Actor.system, null, "company_subscription",
					companyId, companyId, "success",
					next == SubscriptionState.EXPIRED_READ_ONLY ? "trial_expired" : "read_only_ended", from.name(),
					next.name());
			recorded++;
		}
		return recorded;
	}

}
