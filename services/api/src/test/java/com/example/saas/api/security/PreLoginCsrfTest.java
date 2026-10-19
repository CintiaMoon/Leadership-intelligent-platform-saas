package com.example.saas.api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token validity rules of the pre-login CSRF token (D-75), with a controllable clock. */
class PreLoginCsrfTest {

	private static final String KEY = "unit-test-csrf-hmac-key-0123456789abcdef";

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T10:00:00Z"));

	private final Clock clock = new Clock() {

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}

	};

	private PreLoginCsrf csrf(String key) {
		return new PreLoginCsrf(new CsrfProperties(key, "__Host-csrf", true, Duration.ofHours(4), List.of()), clock);
	}

	@Test
	void aFreshTokenIsValidWhenCookieAndHeaderMatch() {
		PreLoginCsrf csrf = csrf(KEY);
		String token = csrf.issue();

		assertThat(csrf.isValid(token, token)).isTrue();
	}

	@Test
	void tokensAreDifferentEachTime() {
		PreLoginCsrf csrf = csrf(KEY);

		assertThat(csrf.issue()).isNotEqualTo(csrf.issue());
	}

	@Test
	void aTokenExpiresAfterTheConfiguredMaxAge() {
		PreLoginCsrf csrf = csrf(KEY);
		String token = csrf.issue();

		now.set(now.get().plus(Duration.ofHours(4)));
		assertThat(csrf.isValid(token, token)).as("exactly at max age").isTrue();

		now.set(now.get().plusSeconds(1));
		assertThat(csrf.isValid(token, token)).as("one second later").isFalse();
	}

	@Test
	void aTokenIssuedFarInTheFutureIsRejected() {
		PreLoginCsrf later = csrf(KEY);
		now.set(now.get().plus(Duration.ofHours(1)));
		String token = later.issue();
		now.set(now.get().minus(Duration.ofHours(1)));

		assertThat(later.isValid(token, token)).isFalse();
	}

	@Test
	void aTokenSignedUnderAnotherKeyIsRejected() {
		String token = csrf(KEY).issue();

		assertThat(csrf("another-unit-test-key-0123456789abcdef0123").isValid(token, token)).isFalse();
	}

	@Test
	void missingEmptyOrMalformedValuesAreRejected() {
		PreLoginCsrf csrf = csrf(KEY);
		String token = csrf.issue();

		assertThat(csrf.isValid(null, token)).isFalse();
		assertThat(csrf.isValid(token, null)).isFalse();
		assertThat(csrf.isValid("", "")).isFalse();
		assertThat(csrf.isValid("a.b", "a.b")).isFalse();
		assertThat(csrf.isValid("a.notanumber.c", "a.notanumber.c")).isFalse();
		assertThat(csrf.isValid(token, token + "x")).isFalse();
	}

	@Test
	void propertiesRejectAShortKeyAndAnInsecureHostCookie() {
		assertThatThrownBy(() -> new CsrfProperties("short", "__Host-csrf", true, Duration.ofHours(1), List.of()))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CsrfProperties(KEY, "__Host-csrf", false, Duration.ofHours(1), List.of()))
			.isInstanceOf(IllegalStateException.class);
		assertThat(new CsrfProperties(KEY, "csrf", false, Duration.ofHours(1), List.of()).cookieSecure()).isFalse();
	}

}
