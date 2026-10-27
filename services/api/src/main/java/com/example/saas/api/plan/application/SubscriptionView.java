package com.example.saas.api.plan.application;

import java.time.Instant;
import java.util.List;

import com.example.saas.api.plan.domain.FeatureKey;
import com.example.saas.api.plan.domain.LimitKey;
import com.example.saas.api.plan.domain.SubscriptionState;

/**
 * What the Owner and Admin may read about the company's plan (Q-13). Every value comes from the server's rows and clock.
 * The plan is null and the lists are empty without a plan.
 */
public record SubscriptionView(PlanRef plan, SubscriptionState state, Instant trialEndsAt, Instant readOnlyEndsAt,
		List<FeatureKey> features, List<LimitView> limits) {

	public record PlanRef(String code, String name, int version) {
	}

	/**
	 * @param enforced whether the application refuses beyond this limit today (members only, Q-12)
	 * @param usage the current count for an enforced limit; null otherwise
	 */
	public record LimitView(LimitKey key, int value, boolean enforced, Integer usage) {
	}

}
