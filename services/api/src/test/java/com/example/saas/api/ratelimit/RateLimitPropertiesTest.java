package com.example.saas.api.ratelimit;

import java.io.InputStream;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rate-limit key has no default: an unset key stops the start-up (ADR-004, D-86). */
class RateLimitPropertiesTest {

	private static RateLimitProperties with(String key) {
		return new RateLimitProperties(key, 4, Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofMinutes(5));
	}

	@Test
	void anUnsetOrEmptyKeyStopsTheStartUp() {
		assertThatThrownBy(() -> with(null)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> with("")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RATE_LIMIT_HMAC_KEY");
	}

	@Test
	void theValueThatWasOnceCommittedIsRefused() {
		assertThatThrownBy(() -> with("vQ8mZ2xKp7N4aR1sT9wE3yH6uJ0cL5dF8gB2nM4xA7kP1rS6tV9zQ3eW5hY0iO2l"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("published");
	}

	@Test
	void theShippedConfigurationHasNoDefaultForTheKey() throws Exception {
		try (InputStream yaml = getClass().getResourceAsStream("/application.yml")) {
			String text = new String(yaml.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			assertThat(text).contains("hmac-key: ${RATE_LIMIT_HMAC_KEY:}");
			assertThat(text).doesNotContain("${RATE_LIMIT_HMAC_KEY:v");
		}
	}

}
