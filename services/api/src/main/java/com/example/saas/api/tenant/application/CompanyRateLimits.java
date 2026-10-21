package com.example.saas.api.tenant.application;

import java.time.Duration;

import com.example.saas.api.ratelimit.RateLimitPolicy;

/** Company-creation limits (SPEC-TEN, D-113): per client address and per account. */
public final class CompanyRateLimits {

	/** 5 creation attempts per client address per hour (AC-TEN-13). */
	public static final RateLimitPolicy CREATE_IP = new RateLimitPolicy("company-create-ip", 5, Duration.ofHours(1));

	/** 10 creation attempts per account per day (AC-TEN-15). */
	public static final RateLimitPolicy CREATE_ACCOUNT = new RateLimitPolicy("company-create-account", 10,
			Duration.ofDays(1));

	private CompanyRateLimits() {
	}

	/**
	 * One denied-permission audit row per user, company and permission per window (D-121). Later denials in the window
	 * are still refused with 403 but not written, so a member repeating a forbidden request cannot flood the trail. The
	 * window is a proposal; the security owner has not set it.
	 */
	public static final RateLimitPolicy DENIAL_AUDIT = new RateLimitPolicy("tenant-denial-audit", 1, Duration.ofHours(1));

}
