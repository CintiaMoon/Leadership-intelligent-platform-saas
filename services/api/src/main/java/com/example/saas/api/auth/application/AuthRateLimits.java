package com.example.saas.api.auth.application;

import java.time.Clock;
import java.time.Duration;

import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitedException;

/** The authentication rate-limit policies (SPEC-AUTH, ADR-004). */
public final class AuthRateLimits {

	/** Registration attempts per client address. */
	public static final RateLimitPolicy REGISTRATION_IP = new RateLimitPolicy("registration-ip", 5, Duration.ofHours(1));

	/**
	 * Signup attempts per normalized email, applied whether or not the account exists, so the notice email to
	 * an existing owner cannot be used to flood their inbox. Not set by the spec: proposed value, to confirm.
	 */
	public static final RateLimitPolicy SIGNUP_ACCOUNT = new RateLimitPolicy("signup-account", 3, Duration.ofHours(24));

	public static final RateLimitPolicy RESEND_ACCOUNT = new RateLimitPolicy("resend-account", 3, Duration.ofHours(1));

	/** Login attempts per client address per minute; also counted for step-up (AC-AUTH-18). */
	public static final RateLimitPolicy LOGIN_IP = new RateLimitPolicy("login-ip", 20, Duration.ofMinutes(1));

	public static final RateLimitPolicy RESEND_IP = new RateLimitPolicy("resend-ip", 10, Duration.ofHours(1));

	private AuthRateLimits() {
	}

	/** Throws {@link RateLimitedException} (429) when an IP-level limit is exceeded. */
	public static void requireWithin(RateLimitResult result, Clock clock) {
		if (!result.allowed()) {
			long seconds = Duration.between(clock.instant(), result.resetsAt()).toSeconds();
			throw new RateLimitedException((int) Math.max(1, seconds));
		}
	}

}
