package com.example.saas.api.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the PostgreSQL-backed counter store (ADR-004, D-86).
 *
 * @param hmacKey secret used to hash identifiers before they are stored; required, at least 32 characters
 * @param poolSize size of the dedicated counter pool, kept small so a flood cannot exhaust business traffic
 * @param connectionTimeout how long a counter write waits for a connection; exceeding it means "store unavailable"
 * @param queryTimeout how long a counter statement may run
 * @param sweepInterval delay between expired-counter sweeps
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
		String hmacKey,
		@DefaultValue("4") int poolSize,
		@DefaultValue("250ms") Duration connectionTimeout,
		@DefaultValue("1s") Duration queryTimeout,
		@DefaultValue("5m") Duration sweepInterval) {

	static final int MIN_KEY_LENGTH = 32;

	public RateLimitProperties {
		if (hmacKey == null || hmacKey.length() < MIN_KEY_LENGTH) {
			throw new IllegalStateException("app.rate-limit.hmac-key (RATE_LIMIT_HMAC_KEY) must be set to a random value of at least "
					+ MIN_KEY_LENGTH + " characters. Generate one with: openssl rand -base64 48");
		}
		com.example.saas.api.security.SecretQuality.requireNotPlaceholder("app.rate-limit.hmac-key", hmacKey);
		if (poolSize < 1) {
			throw new IllegalStateException("app.rate-limit.pool-size must be at least 1");
		}
		if (connectionTimeout.toMillis() < 250) {
			throw new IllegalStateException("app.rate-limit.connection-timeout must be at least 250ms");
		}
	}

}
