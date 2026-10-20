package com.example.saas.api.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The known-device cookie (D-99): set at successful login, it exempts the client from the account-wide failure
 * ceiling. Same rules as the other cookies: {@code __Host-} needs Secure, and local HTTP development renames it.
 */
@ConfigurationProperties("app.security.device")
public record DeviceProperties(
		@DefaultValue("__Host-device") String cookieName,
		@DefaultValue("true") boolean cookieSecure,
		@DefaultValue("90d") Duration lifetime) {

	public DeviceProperties {
		if (cookieName.startsWith("__Host-") && !cookieSecure) {
			throw new IllegalStateException("A __Host- cookie must be Secure; rename the cookie for local HTTP development");
		}
	}

}
