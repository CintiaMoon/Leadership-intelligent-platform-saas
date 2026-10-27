package com.example.saas.api.plan.domain;

import static com.example.saas.api.plan.domain.SubscriptionState.ACTIVE;
import static com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_LOCKED;
import static com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_READ_ONLY;
import static com.example.saas.api.plan.domain.SubscriptionState.NO_PLAN;
import static com.example.saas.api.plan.domain.SubscriptionState.TRIALING;

/**
 * The table of allowed transitions (Q-07, AC-PLAN-17). The same table is enforced by a trigger in V15; this class lets
 * the application refuse before it reaches the database and is tested cell by cell.
 */
public final class SubscriptionTransitions {

	public enum Outcome {

		/** The transition is in the table and changes the state. */
		APPLY,

		/** The state is already the target: nothing changes and no audit event is written (AC-PLAN-11). */
		NO_CHANGE,

		/** The transition is not in the table (AC-PLAN-17). */
		REFUSED

	}

	private SubscriptionTransitions() {
	}

	/**
	 * Whether {@code from} may become {@code to}. ACTIVE to ACTIVE is a change of plan and is decided by the caller, who
	 * compares the plans; here it reports {@link Outcome#NO_CHANGE}.
	 */
	public static Outcome check(SubscriptionState from, SubscriptionState to) {
		if (from == to) {
			return Outcome.NO_CHANGE;
		}
		boolean byTime = (from == TRIALING && to == EXPIRED_READ_ONLY) || (from == EXPIRED_READ_ONLY && to == EXPIRED_LOCKED);
		boolean byOperator = to == ACTIVE && (from == TRIALING || from == EXPIRED_READ_ONLY || from == EXPIRED_LOCKED
				|| from == NO_PLAN);
		return byTime || byOperator ? Outcome.APPLY : Outcome.REFUSED;
	}

	/** True for the two transitions the clock causes. */
	public static boolean isByTime(SubscriptionState from, SubscriptionState to) {
		return (from == TRIALING && to == EXPIRED_READ_ONLY) || (from == EXPIRED_READ_ONLY && to == EXPIRED_LOCKED);
	}

}
