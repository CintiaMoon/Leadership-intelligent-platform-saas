package com.example.saas.api.auth.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hashed single-use tokens (D-81). Consumption is one conditional {@code UPDATE ... RETURNING}, so when
 * several requests present the same token exactly one succeeds, with no read-then-write gap.
 */
@Repository
public class VerificationTokens {

	public enum Purpose {

		EMAIL_VERIFICATION, PASSWORD_RESET

	}

	private final JdbcClient jdbc;

	VerificationTokens(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** At most this many tokens of one purpose are valid for a user at once (D-98). */
	public static final int MAX_LIVE = 3;

	/**
	 * Stores a new token. Earlier unused tokens stay valid, so a stranger who requests a link cannot cancel the
	 * owner's pending one; but no more than {@link #MAX_LIVE} stay valid, and the oldest is invalidated first.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void issue(UUID userId, Purpose purpose, String tokenHash, Instant now, Duration lifetime) {
		jdbc.sql("""
				INSERT INTO verification_token (user_id, purpose, token_hash, expires_at, created_at)
				VALUES (:userId, :purpose, :hash, :expiresAt, :now)
				""")
			.param("userId", userId)
			.param("purpose", purpose.name())
			.param("hash", tokenHash)
			.param("expiresAt", utc(now.plus(lifetime)))
			.param("now", utc(now))
			.update();
		jdbc.sql("""
				UPDATE verification_token SET consumed_at = :now
				WHERE id IN (SELECT id FROM verification_token
				             WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now
				             ORDER BY created_at DESC, id DESC OFFSET :keep)
				""")
			.param("now", utc(now))
			.param("userId", userId)
			.param("purpose", purpose.name())
			.param("keep", MAX_LIVE)
			.update();
	}

	/**
	 * A cheap, non-consuming check used to refuse an unusable token before expensive work. It does not reserve
	 * the token: {@link #consume} remains the only authority, and it decides atomically.
	 */
	public boolean isUsable(String tokenHash, Purpose purpose, Instant now) {
		return Boolean.TRUE.equals(jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM verification_token
				               WHERE token_hash = :hash AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now)
				""")
			.param("hash", tokenHash)
			.param("purpose", purpose.name())
			.param("now", utc(now))
			.query(Boolean.class)
			.single());
	}

	/**
	 * Atomically consumes a token that is unknown-safe: returns the owning user only if the token exists,
	 * matches the purpose, is unexpired, and was not consumed before.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UUID> consume(String tokenHash, Purpose purpose, Instant now) {
		return jdbc.sql("""
				UPDATE verification_token SET consumed_at = :now
				WHERE token_hash = :hash AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now
				RETURNING user_id
				""")
			.param("now", utc(now))
			.param("hash", tokenHash)
			.param("purpose", purpose.name())
			.query(UUID.class)
			.optional();
	}

	/** Invalidates all of a user's unconsumed tokens of one purpose, for example after one of them was used. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void invalidateOutstanding(UUID userId, Purpose purpose, Instant now) {
		jdbc.sql("""
				UPDATE verification_token SET consumed_at = :now
				WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL
				""")
			.param("now", utc(now))
			.param("userId", userId)
			.param("purpose", purpose.name())
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
