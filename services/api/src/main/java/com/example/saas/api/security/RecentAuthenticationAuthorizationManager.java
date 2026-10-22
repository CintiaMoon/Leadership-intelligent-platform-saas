package com.example.saas.api.security;

import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Requires a login or step-up within the recent-authentication window (AC-AUTH-14, 22). Use it with
 * {@code .access(recentAuthentication)} on the sensitive operations: ownership transfer, company deletion,
 * promoting or demoting an Admin, and data export. A stale value is rejected, never queued. No such operation
 * exists yet; the mechanism is tested through a test-only endpoint.
 */
@Component
public class RecentAuthenticationAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

	/** Result for an authenticated session whose last authentication is too old. */
	public record Stale() implements AuthorizationResult {

		@Override
		public boolean isGranted() {
			return false;
		}

	}

	private final RecentAuthentication recentAuthentication;

	RecentAuthenticationAuthorizationManager(RecentAuthentication recentAuthentication) {
		this.recentAuthentication = recentAuthentication;
	}

	@Override
	public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
			RequestAuthorizationContext context) {
		Authentication current = authentication.get();
		if (!(current instanceof SessionAuthentication session)) {
			return new AuthorizationDecision(false);
		}
		return recentAuthentication.isRecent(session.user()) ? new AuthorizationDecision(true) : new Stale();
	}

}
