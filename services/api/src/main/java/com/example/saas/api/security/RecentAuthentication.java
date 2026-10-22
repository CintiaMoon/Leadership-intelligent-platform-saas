package com.example.saas.api.security;

import java.time.Clock;

import org.springframework.stereotype.Component;

/**
 * Whether the session's last login or step-up is recent enough for a sensitive operation (AC-AUTH-14, 22; D-127).
 * The URL-level {@link RecentAuthenticationAuthorizationManager} cannot be used when the need depends on the request
 * body, such as promoting a member to Admin; services call {@link #require} instead. Same window, same rule.
 */
@Component
public class RecentAuthentication {

	private final SessionProperties properties;

	private final Clock clock;

	RecentAuthentication(SessionProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/** True up to and including the end of the window; activity never extends it (AC-AUTH-22). */
	public boolean isRecent(AuthenticatedUser user) {
		return !clock.instant().isAfter(user.lastAuthenticatedAt().plus(properties.recentAuthenticationWindow()));
	}

	/**
	 * @throws ReauthenticationRequiredException the last authentication is older than the window (403
	 *         {@code REAUTHENTICATION_REQUIRED}); the request is rejected, never queued
	 */
	public void require(AuthenticatedUser user) {
		if (!isRecent(user)) {
			throw new ReauthenticationRequiredException();
		}
	}

}
