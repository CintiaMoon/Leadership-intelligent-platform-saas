package com.example.saas.api.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hashes policy name and identifier with HMAC-SHA-256 so raw emails and IP addresses are never stored in
 * the counter table (D-86). The policy name is part of the input, so identical identifiers under two
 * policies produce different keys.
 */
public final class KeyHasher {

	private static final String ALGORITHM = "HmacSHA256";

	private final SecretKeySpec key;

	public KeyHasher(String secret) {
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	public String hash(String policyName, String identifier) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			mac.update(policyName.getBytes(StandardCharsets.UTF_8));
			mac.update((byte) 0);
			mac.update(identifier.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(mac.doFinal());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
		}
	}

}
