package com.example.saas.api.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Session rules that Spring Session does not provide (SPEC-AUTH, D-82). The idle timeout (30 minutes) is
 * {@code spring.session.timeout}.
 *
 * @param absoluteLifetime a session ends this long after login regardless of activity (12 hours, AC-AUTH-16)
 * @param recentAuthenticationWindow how recent a login or step-up must be for sensitive operations (10 minutes,
 *        AC-AUTH-14)
 * @param cookieName the session cookie; the {@code __Host-} prefix needs Secure, Path=/, and no Domain
 * @param cookieSecure always true outside local development, where plain HTTP forces it off and the name must
 *        drop the prefix (a documented development-only override)
 */
@ConfigurationProperties("app.security.session")
public record SessionProperties(
		@DefaultValue("12h") Duration absoluteLifetime,
		@DefaultValue("10m") Duration recentAuthenticationWindow,
		@DefaultValue("__Host-session") String cookieName,
		@DefaultValue("true") boolean cookieSecure) {

	public SessionProperties {
		if (absoluteLifetime.isNegative() || absoluteLifetime.isZero() || recentAuthenticationWindow.isNegative()
				|| recentAuthenticationWindow.isZero()) {
			throw new IllegalStateException("app.security.session durations must be positive");
		}
		if (cookieName.startsWith("__Host-") && !cookieSecure) {
			throw new IllegalStateException("A __Host- cookie must be Secure; rename the cookie for local HTTP development");
		}
	}

}
