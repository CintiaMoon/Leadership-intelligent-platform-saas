package com.example.saas.api.security;

import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Stops the application from starting in a deployed profile with the development default for the trusted proxies
 * (D-95). With only loopback trusted, the BFF's forwarded client address is ignored and every user shares the BFF's
 * own address, so one person's failed logins throttle everyone. Run locally with {@code SPRING_PROFILES_ACTIVE=local}.
 */
@Component
class TrustedProxyGuard {

	private static final Set<String> LOOPBACK_ONLY = Set.of("127.0.0.1/32", "::1/128");

	TrustedProxyGuard(SecurityProperties properties, Environment environment) {
		check(properties.trustedProxyCidrs(), environment.acceptsProfiles(Profiles.of("local", "test")));
	}

	static void check(List<String> trustedProxyCidrs, boolean localOrTest) {
		if (!localOrTest && LOOPBACK_ONLY.containsAll(trustedProxyCidrs)) {
			throw new IllegalStateException("app.security.trusted-proxy-cidrs (TRUSTED_PROXY_CIDRS) is empty or loopback "
					+ "only. Set it to the subnet of the BFF, or run with SPRING_PROFILES_ACTIVE=local for development.");
		}
	}

}
