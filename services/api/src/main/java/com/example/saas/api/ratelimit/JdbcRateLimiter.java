package com.example.saas.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Fixed-window counters in PostgreSQL, so limits hold across application instances (ADR-004, D-86).
 *
 * <p>Each hit is one atomic {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}, which holds only a
 * row lock for the statement. An in-memory counter sits in front: this instance's own attempts are
 * always a lower bound of the shared count, so once they exceed the limit the request can be refused
 * without a database round trip. That short-cut only ever refuses; it never allows.
 */
public final class JdbcRateLimiter implements RateLimiter {

	private static final String UPSERT = """
			INSERT INTO rate_limit_counter (policy, key_hash, window_start, hit_count, expires_at)
			VALUES (?, ?, ?, 1, ?)
			ON CONFLICT (policy, key_hash, window_start)
			DO UPDATE SET hit_count = rate_limit_counter.hit_count + 1
			RETURNING hit_count
			""";

	private static final String SWEEP = """
			DELETE FROM rate_limit_counter
			WHERE ctid IN (SELECT ctid FROM rate_limit_counter WHERE expires_at < ? LIMIT ?)
			""";

	static final int SWEEP_BATCH = 5000;

	private record LocalKey(String policy, String keyHash, long windowStart) {
	}

	private final RateLimitStore store;

	private final KeyHasher hasher;

	private final Clock clock;

	private final Cache<LocalKey, AtomicInteger> localCounts = Caffeine.newBuilder()
		.maximumSize(100_000)
		.expireAfterWrite(Duration.ofHours(1))
		.build();

	public JdbcRateLimiter(RateLimitStore store, KeyHasher hasher, Clock clock) {
		this.store = store;
		this.hasher = hasher;
		this.clock = clock;
	}

	@Override
	public RateLimitResult hit(RateLimitPolicy policy, String identifier) {
		long windowSeconds = policy.window().toSeconds();
		long windowStartEpoch = (clock.instant().getEpochSecond() / windowSeconds) * windowSeconds;
		Instant windowStart = Instant.ofEpochSecond(windowStartEpoch);
		Instant windowEnd = windowStart.plusSeconds(windowSeconds);
		String keyHash = hasher.hash(policy.name(), identifier);

		int local = localCounts.get(new LocalKey(policy.name(), keyHash, windowStartEpoch), k -> new AtomicInteger())
			.incrementAndGet();
		if (local > policy.limit()) {
			return new RateLimitResult(false, local, policy.limit(), windowEnd);
		}

		int count;
		try {
			Integer value = store.jdbc()
				.queryForObject(UPSERT, Integer.class, policy.name(), keyHash, utc(windowStart), utc(windowEnd));
			count = value == null ? Integer.MAX_VALUE : value;
		}
		catch (RuntimeException ex) {
			throw new RateLimitStoreUnavailableException(ex);
		}
		return new RateLimitResult(count <= policy.limit(), count, policy.limit(), windowEnd);
	}

	/** Deletes expired counters in batches and returns how many rows were removed. */
	public int sweepExpired() {
		int total = 0;
		int deleted;
		do {
			deleted = store.jdbc().update(SWEEP, utc(clock.instant()), SWEEP_BATCH);
			total += deleted;
		}
		while (deleted == SWEEP_BATCH);
		return total;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
