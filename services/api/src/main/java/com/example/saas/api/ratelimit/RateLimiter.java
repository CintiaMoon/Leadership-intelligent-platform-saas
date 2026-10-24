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

	/**
	 * Reads the count in the current window without adding to it, for limits that count only some outcomes (failed
	 * invitation tokens, D-152). {@link RateLimitResult#allowed()} is true while one more attempt would still be within
	 * the limit, so with a limit of 10, ten recorded failures make the next attempt refused.
	 *
	 * @throws RateLimitStoreUnavailableException if the counter cannot be read; callers fail closed
	 */
	RateLimitResult current(RateLimitPolicy policy, String identifier);

}
