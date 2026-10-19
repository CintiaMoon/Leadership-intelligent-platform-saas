package com.example.saas.api.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Verification and reset tokens (D-81): 256 bits from {@link SecureRandom}, base64url without padding. Only
 * the SHA-256 of a token is stored. A fast hash is right here because the input already has full entropy;
 * slow password hashing is for low-entropy secrets.
 */
public final class TokenCodec {

	private static final int TOKEN_BYTES = 32;

	/** 32 bytes in unpadded base64url. */
	public static final int TOKEN_LENGTH = 43;

	private static final SecureRandom RANDOM = new SecureRandom();

	private TokenCodec() {
	}

	public static String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Lowercase hex SHA-256 of the token text, as stored in {@code verification_token.token_hash}. */
	public static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
