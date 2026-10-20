package com.example.saas.api.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Trust settings for the BFF boundary (ADR-001, D-50, D-91).
 *
 * @param bffCredentials shared secrets that authenticate the BFF as a client; two may be valid during
 *        rotation. Empty means no BFF is configured and every /api request is rejected.
 * @param trustedProxyCidrs addresses of the BFF and ingress hops whose forwarded headers are honored
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
		@DefaultValue List<String> bffCredentials,
		@DefaultValue({ "127.0.0.1/32", "::1/128" }) List<String> trustedProxyCidrs) {

	/** 256 bits encoded as base64url is 43 characters; shorter values cannot carry that entropy. */
	static final int MIN_CREDENTIAL_LENGTH = 43;

	static final int MAX_CREDENTIALS = 2;

	public SecurityProperties {
		bffCredentials = bffCredentials.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
		if (bffCredentials.size() > MAX_CREDENTIALS) {
			throw new IllegalStateException("app.security.bff-credentials allows at most " + MAX_CREDENTIALS
					+ " values (current and previous during rotation)");
		}
		if (bffCredentials.stream().anyMatch(value -> value.length() < MIN_CREDENTIAL_LENGTH)) {
			throw new IllegalStateException("Each BFF credential must be at least " + MIN_CREDENTIAL_LENGTH
					+ " characters (256 bits). Generate one with: openssl rand -base64 48");
		}
		bffCredentials.forEach(value -> SecretQuality.requireNotPlaceholder("A BFF credential", value));
		trustedProxyCidrs = trustedProxyCidrs.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
	}

}
