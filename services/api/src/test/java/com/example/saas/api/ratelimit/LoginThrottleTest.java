package com.example.saas.api.ratelimit;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The escalation rule of D-78: 1, 2, 4, 8, then 15 minutes (cap), never indefinite. */
class LoginThrottleTest {

	@Test
	void belowTheThresholdNothingIsBlocked() {
		assertThat(LoginThrottle.blockDuration(0, LoginThrottle.PAIR_THRESHOLD)).isZero();
		assertThat(LoginThrottle.blockDuration(4, LoginThrottle.PAIR_THRESHOLD)).isZero();
		assertThat(LoginThrottle.blockDuration(19, LoginThrottle.ACCOUNT_THRESHOLD)).isZero();
	}

	@Test
	void theBlockDoublesWithEachFailureAfterTheThreshold() {
		int threshold = LoginThrottle.PAIR_THRESHOLD;

		assertThat(LoginThrottle.blockDuration(threshold, threshold)).isEqualTo(Duration.ofMinutes(1));
		assertThat(LoginThrottle.blockDuration(threshold + 1, threshold)).isEqualTo(Duration.ofMinutes(2));
		assertThat(LoginThrottle.blockDuration(threshold + 2, threshold)).isEqualTo(Duration.ofMinutes(4));
		assertThat(LoginThrottle.blockDuration(threshold + 3, threshold)).isEqualTo(Duration.ofMinutes(8));
	}

	@Test
	void theBlockIsCappedAtFifteenMinutesHoweverManyFailuresThereAre() {
		int threshold = LoginThrottle.ACCOUNT_THRESHOLD;

		assertThat(LoginThrottle.blockDuration(threshold + 4, threshold)).isEqualTo(Duration.ofMinutes(15));
		assertThat(LoginThrottle.blockDuration(threshold + 50, threshold)).isEqualTo(Duration.ofMinutes(15));
		assertThat(LoginThrottle.blockDuration(Integer.MAX_VALUE, threshold)).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void theThresholdsAndWindowsAreTheOnesInTheSpecification() {
		assertThat(LoginThrottle.PAIR_THRESHOLD).isEqualTo(5);
		assertThat(LoginThrottle.PAIR_WINDOW).isEqualTo(Duration.ofMinutes(15));
		assertThat(LoginThrottle.ACCOUNT_THRESHOLD).isEqualTo(20);
		assertThat(LoginThrottle.ACCOUNT_WINDOW).isEqualTo(Duration.ofHours(1));
		assertThat(LoginThrottle.MAX_BLOCK).isEqualTo(Duration.ofMinutes(15));
	}

}
