package com.example.saas.api.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Stateless signed double-submit tokens for requests made before a session exists (D-75).
 *
 * <p>A token is {@code random.issuedAt.signature}, where the signature is an HMAC-SHA-256 under a server
 * key. The same value is set in a cookie and must come back in the {@code X-CSRF-TOKEN} header: an attacker
 * on another site can neither read the cookie nor forge a validly signed value, so a cross-site request
 * fails. Nothing is stored on the server, so issuing tokens cannot be used to fill the database.
 */
@Component
public class PreLoginCsrf {

	private static final String ALGORITHM = "HmacSHA256";

	private static final long CLOCK_SKEW_SECONDS = 60;

	private final SecretKeySpec key;

	private final CsrfProperties properties;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	PreLoginCsrf(CsrfProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.key = new SecretKeySpec(properties.hmacKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	public String issue() {
		byte[] nonce = new byte[16];
		random.nextBytes(nonce);
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "."
				+ clock.instant().getEpochSecond();
		return payload + "." + sign(payload);
	}

	/** True only if both values are present, identical, correctly signed, and not expired. */
	public boolean isValid(String cookieValue, String headerValue) {
		if (cookieValue == null || headerValue == null || cookieValue.isEmpty()) {
			return false;
		}
		if (!MessageDigest.isEqual(cookieValue.getBytes(StandardCharsets.UTF_8),
				headerValue.getBytes(StandardCharsets.UTF_8))) {
			return false;
		}
		String[] parts = headerValue.split("\\.", -1);
		if (parts.length != 3) {
			return false;
		}
		String payload = parts[0] + "." + parts[1];
		if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
				parts[2].getBytes(StandardCharsets.UTF_8))) {
			return false;
		}
		long issuedAt;
		try {
			issuedAt = Long.parseLong(parts[1]);
		}
		catch (NumberFormatException ex) {
			return false;
		}
		Instant now = clock.instant();
		return issuedAt <= now.getEpochSecond() + CLOCK_SKEW_SECONDS
				&& now.getEpochSecond() - issuedAt <= properties.maxAge().toSeconds();
	}

	private String sign(String payload) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
		}
	}

}
