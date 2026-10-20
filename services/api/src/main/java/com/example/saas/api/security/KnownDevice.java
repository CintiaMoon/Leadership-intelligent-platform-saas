package com.example.saas.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Signed "this browser has logged in to this account before" marker (D-99). The value is
 * {@code userId.issuedAt.signature}: the signature covers the account, so a cookie made for one account proves
 * nothing about another, and it is useless without the server's key. It only relaxes the account-wide failure
 * ceiling; the per-(account, address) block and the per-address limits still apply.
 */
@Component
public class KnownDevice {

	private static final String ALGORITHM = "HmacSHA256";

	private static final String CONTEXT = "known-device|";

	private final DeviceProperties properties;

	private final Clock clock;

	private final SecretKeySpec key;

	KnownDevice(DeviceProperties properties, CsrfProperties csrf, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.key = new SecretKeySpec(csrf.hmacKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	public String cookieName() {
		return properties.cookieName();
	}

	/** The Set-Cookie value that marks this browser as known for {@code userId}. */
	public String issueCookie(UUID userId) {
		long issuedAt = clock.instant().getEpochSecond();
		String payload = userId + "." + issuedAt;
		String value = payload + "." + sign(payload);
		return ResponseCookie.from(properties.cookieName(), value)
			.httpOnly(true)
			.secure(properties.cookieSecure())
			.sameSite("Lax")
			.path("/")
			.maxAge(properties.lifetime())
			.build()
			.toString();
	}

	/** True only for a correctly signed, unexpired cookie that was issued for exactly this account. */
	public boolean isKnown(String cookieValue, UUID userId) {
		if (cookieValue == null) {
			return false;
		}
		String[] parts = cookieValue.split("[.]");
		if (parts.length != 3 || !parts[0].equals(userId.toString())) {
			return false;
		}
		long issuedAt;
		try {
			issuedAt = Long.parseLong(parts[1]);
		}
		catch (NumberFormatException ex) {
			return false;
		}
		long now = clock.instant().getEpochSecond();
		if (issuedAt > now + 60 || now - issuedAt > properties.lifetime().toSeconds()) {
			return false;
		}
		String expected = sign(parts[0] + "." + parts[1]);
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				parts[2].getBytes(StandardCharsets.UTF_8));
	}

	private String sign(String payload) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal((CONTEXT + payload).getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
		}
	}

}
