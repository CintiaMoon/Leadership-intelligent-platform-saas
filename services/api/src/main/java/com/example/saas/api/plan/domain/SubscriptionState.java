package com.example.saas.api.plan.domain;

import java.time.Instant;

/**
 * The states of a company's subscription (SPEC-PLAN). PAST_DUE and CANCELED are reserved for a billing slice and do not
 * exist yet (D-66).
 */
public enum SubscriptionState {

	TRIALING, EXPIRED_READ_ONLY, EXPIRED_LOCKED, ACTIVE, NO_PLAN;

	/**
	 * The state that holds at {@code now}, from the recorded state and the trial instants, so that a request after an end
	 * instant is evaluated as expired even if the sweep has not recorded it (AC-PLAN-10). A trial is expired when
	 * {@code now} is greater than or equal to its end instant (AC-PLAN-18), and the read-only window ends at its end
	 * instant in the same way. ACTIVE and NO_PLAN are not time-based. Reading never writes.
	 */
	public static SubscriptionState effective(SubscriptionState recorded, Instant trialEndsAt, Instant readOnlyEndsAt,
			Instant now) {
		if (recorded == ACTIVE || recorded == NO_PLAN) {
			return recorded;
		}
		if (trialEndsAt == null || readOnlyEndsAt == null) {
			// A trial state without its instants cannot happen (the database refuses it): fail closed.
			return EXPIRED_LOCKED;
		}
		SubscriptionState byTime = now.isBefore(trialEndsAt) ? TRIALING
				: now.isBefore(readOnlyEndsAt) ? EXPIRED_READ_ONLY : EXPIRED_LOCKED;
		// Time only moves forward: a recorded later state is never undone by a clock that reads earlier.
		return byTime.ordinal() >= recorded.ordinal() ? byTime : recorded;
	}

}
