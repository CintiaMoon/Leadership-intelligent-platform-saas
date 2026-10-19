package com.example.saas.api.ratelimit;

/**
 * The counter write timed out or the connection failed. Callers on login, registration, resend,
 * password-reset, invitation-token, and company-creation endpoints must reject the request (fail
 * closed, ADR-004). The cause is kept for diagnosis but its message is never returned to clients.
 */
public class RateLimitStoreUnavailableException extends RuntimeException {

	public RateLimitStoreUnavailableException(Throwable cause) {
		super("Rate-limit store unavailable", cause);
	}

}
