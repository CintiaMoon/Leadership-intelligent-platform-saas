package com.example.saas.api.tenant.deletion.application;

import java.time.Duration;

import com.example.saas.api.ratelimit.RateLimitPolicy;

/**
 * Limits on requesting and cancelling a deletion (plan Q-26, AC-DEL-28). A request and a cancellation count together. Read
 * before the operation and counted after it commits, so a refused request does not use the allowance up (the pattern of
 * D-164).
 */
public final class DeletionRateLimits {

	/** 5 requests and cancellations per user per hour. */
	public static final RateLimitPolicy PER_USER = new RateLimitPolicy("deletion-user", 5, Duration.ofHours(1));

	/** 10 requests and cancellations per company per hour. */
	public static final RateLimitPolicy PER_COMPANY = new RateLimitPolicy("deletion-company", 10, Duration.ofHours(1));

	private DeletionRateLimits() {
	}

}
