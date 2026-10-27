package com.example.saas.api.plan;

import java.util.Set;

import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.plan.domain.SubscriptionTransitions;
import com.example.saas.api.plan.domain.SubscriptionTransitions.Outcome;
import org.junit.jupiter.api.Test;

import static com.example.saas.api.plan.domain.SubscriptionState.ACTIVE;
import static com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_LOCKED;
import static com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_READ_ONLY;
import static com.example.saas.api.plan.domain.SubscriptionState.NO_PLAN;
import static com.example.saas.api.plan.domain.SubscriptionState.TRIALING;
import static org.assertj.core.api.Assertions.assertThat;

/** The transition table, cell by cell (Q-07; AC-PLAN-11, 17): all 25 pairs, so a changed cell fails a test. */
class SubscriptionTransitionsTest {

	private static final Set<String> ALLOWED = Set.of("TRIALING>EXPIRED_READ_ONLY", "EXPIRED_READ_ONLY>EXPIRED_LOCKED",
			"TRIALING>ACTIVE", "EXPIRED_READ_ONLY>ACTIVE", "EXPIRED_LOCKED>ACTIVE", "NO_PLAN>ACTIVE");

	@Test
	void everyPairIsDecidedAsTheSpecTableSays() {
		for (SubscriptionState from : SubscriptionState.values()) {
			for (SubscriptionState to : SubscriptionState.values()) {
				Outcome expected = from == to ? Outcome.NO_CHANGE
						: ALLOWED.contains(from + ">" + to) ? Outcome.APPLY : Outcome.REFUSED;
				assertThat(SubscriptionTransitions.check(from, to)).as("%s to %s", from, to).isEqualTo(expected);
			}
		}
	}

	@Test
	void onlyTheTwoTrialStepsAreByTime() {
		for (SubscriptionState from : SubscriptionState.values()) {
			for (SubscriptionState to : SubscriptionState.values()) {
				boolean expected = (from == TRIALING && to == EXPIRED_READ_ONLY)
						|| (from == EXPIRED_READ_ONLY && to == EXPIRED_LOCKED);
				assertThat(SubscriptionTransitions.isByTime(from, to)).as("%s to %s", from, to).isEqualTo(expected);
			}
		}
	}

	@Test
	void noStateLeavesActiveExceptToActiveAndNothingReturnsToATrial() {
		for (SubscriptionState to : SubscriptionState.values()) {
			if (to != ACTIVE) {
				assertThat(SubscriptionTransitions.check(ACTIVE, to)).isEqualTo(Outcome.REFUSED);
			}
		}
		for (SubscriptionState from : SubscriptionState.values()) {
			if (from != TRIALING) {
				assertThat(SubscriptionTransitions.check(from, TRIALING)).isEqualTo(Outcome.REFUSED);
			}
			if (from != NO_PLAN) {
				assertThat(SubscriptionTransitions.check(from, NO_PLAN)).isEqualTo(Outcome.REFUSED);
			}
		}
		assertThat(SubscriptionTransitions.check(TRIALING, EXPIRED_LOCKED)).isEqualTo(Outcome.REFUSED);
		assertThat(SubscriptionTransitions.check(EXPIRED_LOCKED, EXPIRED_READ_ONLY)).isEqualTo(Outcome.REFUSED);
	}

}
