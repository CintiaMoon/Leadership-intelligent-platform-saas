package com.example.saas.api.ratelimit;

import java.time.Duration;

/**
 * A named fixed-window limit, for example {@code new RateLimitPolicy("registration-ip", 5, Duration.ofHours(1))}.
 * The name is part of the stored key, so two policies never share counters.
 */
public record RateLimitPolicy(String name, int limit, Duration window) {

	public RateLimitPolicy {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Policy name is required");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("Limit must be at least 1");
		}
		if (window == null || window.toSeconds() < 1) {
			throw new IllegalArgumentException("Window must be at least one second");
		}
	}

}
