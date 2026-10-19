package com.example.saas.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Escalating block windows for failed logins (SPEC-AUTH, D-51, D-78, AC-AUTH-18, 25, 26).
 *
 * <p>Two counters are kept in PostgreSQL, so they hold across instances: failures per (account, client address)
 * pair over 15 minutes, and failures per account alone over one hour. After the 5th pair failure, or the 20th
 * account failure, further attempts are refused for 1 minute after the latest failure; each further failure
 * doubles that (2, 4, 8) up to a cap of 15 minutes. While refused the password is not evaluated and the caller
 * returns the ordinary login failure. Blocked attempts are not counted, so an attacker cannot extend a block by
 * hammering it, and nothing here ever locks an account: every block ends on its own.
 *
 * <p>Counter keys are HMACs of the normalized email and address, never the raw values. If the store cannot be
 * reached a {@link RateLimitStoreUnavailableException} is thrown and login fails closed (AC-AUTH-21).
 */
public final class LoginThrottle {

	static final int PAIR_THRESHOLD = 5;

	static final Duration PAIR_WINDOW = Duration.ofMinutes(15);

	static final int ACCOUNT_THRESHOLD = 20;

	static final Duration ACCOUNT_WINDOW = Duration.ofHours(1);

	static final Duration MAX_BLOCK = Duration.ofMinutes(15);

	private static final String UPSERT = """
			INSERT INTO login_throttle (key_hash, failures, window_start, last_failure_at, expires_at)
			VALUES (?, 1, ?, ?, ?)
			ON CONFLICT (key_hash) DO UPDATE SET
			    failures = CASE WHEN login_throttle.expires_at <= ? THEN 1 ELSE login_throttle.failures + 1 END,
			    window_start = CASE WHEN login_throttle.expires_at <= ? THEN ? ELSE login_throttle.window_start END,
			    expires_at = CASE WHEN login_throttle.expires_at <= ? THEN ? ELSE login_throttle.expires_at END,
			    last_failure_at = ?
			""";

	private static final String SWEEP = """
			DELETE FROM login_throttle
			WHERE ctid IN (SELECT ctid FROM login_throttle WHERE expires_at < ? LIMIT ?)
			""";

	private record Row(int failures, Instant windowEnd, Instant lastFailureAt) {
	}

	private final RateLimitStore store;

	private final KeyHasher hasher;

	private final Clock clock;

	public LoginThrottle(RateLimitStore store, KeyHasher hasher, Clock clock) {
		this.store = store;
		this.hasher = hasher;
		this.clock = clock;
	}

	/** True if an attempt for this account from this address must be refused right now. */
	public boolean isBlocked(String normalizedEmail, String clientKey) {
		String pairKey = pairKey(normalizedEmail, clientKey);
		String accountKey = accountKey(normalizedEmail);
		Map<String, Row> rows = new HashMap<>();
		try {
			store.jdbc().query("""
					SELECT key_hash, failures, expires_at, last_failure_at FROM login_throttle
					WHERE key_hash IN (?, ?)
					""", rs -> {
				rows.put(rs.getString("key_hash"), new Row(rs.getInt("failures"),
						rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
						rs.getObject("last_failure_at", OffsetDateTime.class).toInstant()));
			}, pairKey, accountKey);
		}
		catch (RuntimeException ex) {
			throw new RateLimitStoreUnavailableException(ex);
		}
		Instant now = clock.instant();
		return blocked(rows.get(pairKey), PAIR_THRESHOLD, now) || blocked(rows.get(accountKey), ACCOUNT_THRESHOLD, now);
	}

	/** Counts one failed attempt against both the pair and the account. Called for unknown accounts too. */
	public void recordFailure(String normalizedEmail, String clientKey) {
		Instant now = clock.instant();
		try {
			upsert(pairKey(normalizedEmail, clientKey), now, PAIR_WINDOW);
			upsert(accountKey(normalizedEmail), now, ACCOUNT_WINDOW);
		}
		catch (RuntimeException ex) {
			throw new RateLimitStoreUnavailableException(ex);
		}
	}

	/** A successful login clears the pair's failures; the account-wide count simply ages out. */
	public void recordSuccess(String normalizedEmail, String clientKey) {
		try {
			store.jdbc().update("DELETE FROM login_throttle WHERE key_hash = ?", pairKey(normalizedEmail, clientKey));
		}
		catch (RuntimeException ex) {
			throw new RateLimitStoreUnavailableException(ex);
		}
	}

	/** Removes expired rows in batches and returns how many were deleted. */
	public int sweepExpired() {
		int total = 0;
		int deleted;
		do {
			deleted = store.jdbc().update(SWEEP, utc(clock.instant()), JdbcRateLimiter.SWEEP_BATCH);
			total += deleted;
		}
		while (deleted == JdbcRateLimiter.SWEEP_BATCH);
		return total;
	}

	/** How long attempts are refused after the latest failure, given the failure count; zero below the threshold. */
	static Duration blockDuration(int failures, int threshold) {
		if (failures < threshold) {
			return Duration.ZERO;
		}
		long minutes = 1L << Math.min(failures - threshold, 10);
		Duration block = Duration.ofMinutes(minutes);
		return block.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : block;
	}

	private static boolean blocked(Row row, int threshold, Instant now) {
		if (row == null || !now.isBefore(row.windowEnd())) {
			return false;
		}
		return now.isBefore(row.lastFailureAt().plus(blockDuration(row.failures(), threshold)));
	}

	private void upsert(String key, Instant now, Duration window) {
		OffsetDateTime at = utc(now);
		OffsetDateTime end = utc(now.plus(window));
		store.jdbc().update(UPSERT, key, at, at, end, at, at, at, at, end, at);
	}

	private String pairKey(String normalizedEmail, String clientKey) {
		return hasher.hash("login-pair", normalizedEmail + "|" + clientKey);
	}

	private String accountKey(String normalizedEmail) {
		return hasher.hash("login-account", normalizedEmail);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
