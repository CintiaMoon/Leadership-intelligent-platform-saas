package com.example.saas.api.plan;

import java.time.Duration;
import java.time.Instant;

import com.example.saas.api.plan.domain.SubscriptionState;
import org.junit.jupiter.api.Test;

import static com.example.saas.api.plan.domain.SubscriptionState.ACTIVE;
import static com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_LOCKED;
import static com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_READ_ONLY;
import static com.example.saas.api.plan.domain.SubscriptionState.NO_PLAN;
import static com.example.saas.api.plan.domain.SubscriptionState.TRIALING;
import static org.assertj.core.api.Assertions.assertThat;

/** The pure time evaluation (Q-06; AC-PLAN-10, 18): the exact boundary instants, one millisecond either side. */
class SubscriptionStateTest {

	private static final Instant TRIAL_END = Instant.parse("2026-11-10T10:00:00Z");

	private static final Instant READ_ONLY_END = Instant.parse("2026-11-24T10:00:00Z");

	private static SubscriptionState at(SubscriptionState recorded, Instant now) {
		return SubscriptionState.effective(recorded, TRIAL_END, READ_ONLY_END, now);
	}

	@Test
	void aTrialRunsUntilItsEndInstantAndIsExpiredAtIt() {
		assertThat(at(TRIALING, TRIAL_END.minusMillis(1))).isEqualTo(TRIALING);
		assertThat(at(TRIALING, TRIAL_END)).isEqualTo(EXPIRED_READ_ONLY);
		assertThat(at(TRIALING, TRIAL_END.plusMillis(1))).isEqualTo(EXPIRED_READ_ONLY);
	}

	@Test
	void theReadOnlyWindowEndsAtItsEndInstant() {
		assertThat(at(TRIALING, READ_ONLY_END.minusMillis(1))).isEqualTo(EXPIRED_READ_ONLY);
		assertThat(at(TRIALING, READ_ONLY_END)).isEqualTo(EXPIRED_LOCKED);
		assertThat(at(EXPIRED_READ_ONLY, READ_ONLY_END.minusMillis(1))).isEqualTo(EXPIRED_READ_ONLY);
		assertThat(at(EXPIRED_READ_ONLY, READ_ONLY_END)).isEqualTo(EXPIRED_LOCKED);
	}

	@Test
	void aRecordedStateIsNeverUndoneByAnEarlierClockReading() {
		assertThat(at(EXPIRED_READ_ONLY, TRIAL_END.minus(Duration.ofDays(1)))).isEqualTo(EXPIRED_READ_ONLY);
		assertThat(at(EXPIRED_LOCKED, TRIAL_END.minus(Duration.ofDays(1)))).isEqualTo(EXPIRED_LOCKED);
	}

	@Test
	void activeAndNoPlanAreNotTimeBased() {
		assertThat(SubscriptionState.effective(ACTIVE, null, null, READ_ONLY_END.plus(Duration.ofDays(400)))).isEqualTo(ACTIVE);
		assertThat(SubscriptionState.effective(NO_PLAN, null, null, READ_ONLY_END.plus(Duration.ofDays(400)))).isEqualTo(NO_PLAN);
		assertThat(at(ACTIVE, READ_ONLY_END.plus(Duration.ofDays(1)))).isEqualTo(ACTIVE);
	}

	@Test
	void aTrialStateWithoutItsInstantsFailsClosed() {
		assertThat(SubscriptionState.effective(TRIALING, null, null, TRIAL_END.minus(Duration.ofDays(1)))).isEqualTo(EXPIRED_LOCKED);
	}

}
