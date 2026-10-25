package com.example.saas.api.tenant.ownership.application;

import java.time.Duration;

import com.example.saas.api.ratelimit.RateLimitPolicy;

/** Ownership transfer limits (AC-OWN-20, D-187). */
public final class OwnershipRateLimits {

	/**
	 * 5 transfers initiated per company per 24 hours. Read before the request and counted after it commits, so a request
	 * that is refused does not use the allowance up (the pattern of D-164).
	 */
	public static final RateLimitPolicy INITIATED_PER_COMPANY = new RateLimitPolicy("ownership-initiated-company", 5,
			Duration.ofDays(1));

	private OwnershipRateLimits() {
	}

}
