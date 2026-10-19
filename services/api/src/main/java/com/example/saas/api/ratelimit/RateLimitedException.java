package com.example.saas.api.ratelimit;

/** An IP-level limit was exceeded; becomes a 429 problem with {@code Retry-After}. */
public class RateLimitedException extends RuntimeException {

	private final int retryAfterSeconds;

	public RateLimitedException(int retryAfterSeconds) {
		super("Rate limit exceeded");
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public int retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
