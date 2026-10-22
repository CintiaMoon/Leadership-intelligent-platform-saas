package com.example.saas.api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecentAuthenticationTest {

	private static final Instant LOGIN = Instant.parse("2026-10-22T10:00:00Z");

	private static final SessionProperties PROPERTIES = new SessionProperties(Duration.ofHours(12),
			Duration.ofMinutes(10), "session", false);

	private static RecentAuthentication at(Instant now) {
		return new RecentAuthentication(PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
	}

	private static AuthenticatedUser user() {
		return new AuthenticatedUser(UUID.randomUUID(), LOGIN, LOGIN.plus(Duration.ofHours(12)));
	}

	@Test
	void theWindowEndsTenMinutesAfterTheLoginAndIncludesItsLastInstant() {
		assertThat(at(LOGIN).isRecent(user())).isTrue();
		assertThat(at(LOGIN.plus(Duration.ofMinutes(10))).isRecent(user())).as("exactly 10m00s").isTrue();
		assertThat(at(LOGIN.plus(Duration.ofMinutes(10)).plusNanos(1)).isRecent(user())).as("one instant later")
			.isFalse();
	}

	@Test
	void requireThrowsOnlyOutsideTheWindow() {
		assertThatCode(() -> at(LOGIN.plus(Duration.ofMinutes(9))).require(user())).doesNotThrowAnyException();
		assertThatThrownBy(() -> at(LOGIN.plus(Duration.ofMinutes(11))).require(user()))
			.isInstanceOf(ReauthenticationRequiredException.class);
	}

	@Test
	void aSessionThatIsOlderButStepsUpIsRecentAgain() {
		AuthenticatedUser steppedUp = new AuthenticatedUser(UUID.randomUUID(), LOGIN.plus(Duration.ofHours(1)),
				LOGIN.plus(Duration.ofHours(12)));

		assertThat(at(LOGIN.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(5))).isRecent(steppedUp)).isTrue();
	}

}
