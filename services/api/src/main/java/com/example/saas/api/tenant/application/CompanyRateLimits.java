package com.example.saas.api.tenant.application;

import java.time.Duration;

import com.example.saas.api.ratelimit.RateLimitPolicy;

/** Company-creation limits (SPEC-TEN, D-107): per client address and per account. */
public final class CompanyRateLimits {

	/** 5 creation attempts per client address per hour (AC-TEN-13). */
	public static final RateLimitPolicy CREATE_IP = new RateLimitPolicy("company-create-ip", 5, Duration.ofHours(1));

	/** 10 creation attempts per account per day (AC-TEN-15). */
	public static final RateLimitPolicy CREATE_ACCOUNT = new RateLimitPolicy("company-create-account", 10,
			Duration.ofDays(1));

	private CompanyRateLimits() {
	}

}
