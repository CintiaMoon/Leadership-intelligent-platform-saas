package com.example.saas.api.ratelimit;

/** Counts one attempt against a policy and says whether it is within the limit. */
public interface RateLimiter {

	/**
	 * Records one hit for {@code identifier} (already normalized by the caller, for example the
	 * normalized email or the client IP key) and reports whether it is allowed.
	 *
	 * @throws RateLimitStoreUnavailableException if the counter cannot be written; callers fail closed
	 */
	RateLimitResult hit(RateLimitPolicy policy, String identifier);

}
