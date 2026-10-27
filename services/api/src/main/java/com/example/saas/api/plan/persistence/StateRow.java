package com.example.saas.api.plan.persistence;

import java.time.Instant;

import com.example.saas.api.plan.domain.SubscriptionState;

/**
 * What a decision needs from a subscription. Read by a query that selects columns, so the answer is always the
 * database's current one: a decision taken under the company lock must see an operator's committed change, which an
 * entity already loaded in the same transaction would hide.
 */
public record StateRow(SubscriptionState state, Instant trialEndsAt, Instant readOnlyEndsAt) {

	public SubscriptionState effectiveAt(Instant now) {
		return SubscriptionState.effective(state, trialEndsAt, readOnlyEndsAt, now);
	}

}
