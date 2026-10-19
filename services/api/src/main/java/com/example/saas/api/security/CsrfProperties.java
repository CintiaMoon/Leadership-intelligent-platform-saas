package com.example.saas.api.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pre-login CSRF settings (D-75, ADR-001).
 *
 * @param hmacKey secret that signs pre-login CSRF tokens; required, at least 32 characters
 * @param cookieName the double-submit cookie; the {@code __Host-} prefix needs Secure, Path=/, and no Domain
 * @param cookieSecure always true outside local development, where plain HTTP forces it off and the name must
 *        drop the prefix (a documented development-only override)
 * @param maxAge how long an issued token stays valid
 * @param allowedOrigins origins the BFF may send in {@code Origin}; if empty the check is skipped. A present
 *        {@code Origin} that is not listed is rejected
 */
@ConfigurationProperties("app.security.csrf")
public record CsrfProperties(
		String hmacKey,
		@DefaultValue("__Host-csrf") String cookieName,
		@DefaultValue("true") boolean cookieSecure,
		@DefaultValue("4h") Duration maxAge,
		@DefaultValue List<String> allowedOrigins) {

	static final int MIN_KEY_LENGTH = 32;

	public CsrfProperties {
		if (hmacKey == null || hmacKey.length() < MIN_KEY_LENGTH) {
			throw new IllegalStateException("app.security.csrf.hmac-key (CSRF_HMAC_KEY) must be set to a random value of at least "
					+ MIN_KEY_LENGTH + " characters. Generate one with: openssl rand -base64 48");
		}
		if (cookieName.startsWith("__Host-") && !cookieSecure) {
			throw new IllegalStateException("A __Host- cookie must be Secure; rename the cookie for local HTTP development");
		}
		allowedOrigins = allowedOrigins.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
	}

}
