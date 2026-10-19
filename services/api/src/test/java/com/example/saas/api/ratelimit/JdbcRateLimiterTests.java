package com.example.saas.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.example.saas.api.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Counter store behavior against a real PostgreSQL (ADR-004, D-86; AC-AUTH-08, AC-AUTH-21). */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class JdbcRateLimiterTests {

	static final String SECRET = "test-rate-limit-hmac-key-0123456789abcdef0123456789abcdef";

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RateLimitProperties properties;

	private final List<RateLimitStore> stores = new ArrayList<>();

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));

	private String policyName;

	@BeforeEach
	void uniquePolicy() {
		policyName = "test-" + UUID.randomUUID();
	}

	@AfterEach
	void closeStores() {
		stores.forEach(RateLimitStore::close);
	}

	private JdbcRateLimiter newInstance() throws Exception {
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		RateLimitStore store = new RateLimitStore(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword(),
				properties);
		stores.add(store);
		return new JdbcRateLimiter(store, new KeyHasher(SECRET), clock);
	}

	@Test
	void allowsUpToTheLimitThenRefuses() throws Exception {
		JdbcRateLimiter limiter = newInstance();
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 5, Duration.ofHours(1));

		for (int i = 1; i <= 5; i++) {
			RateLimitResult result = limiter.hit(policy, "a@example.com");
			assertThat(result.allowed()).as("hit %d", i).isTrue();
			assertThat(result.count()).isEqualTo(i);
		}
		assertThat(limiter.hit(policy, "a@example.com").allowed()).as("6th hit").isFalse();
	}

	@Test
	void identifiersAndPoliciesDoNotShareCounters() throws Exception {
		JdbcRateLimiter limiter = newInstance();
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 1, Duration.ofHours(1));
		RateLimitPolicy other = new RateLimitPolicy(policyName + "-other", 1, Duration.ofHours(1));

		assertThat(limiter.hit(policy, "a@example.com").allowed()).isTrue();
		assertThat(limiter.hit(policy, "a@example.com").allowed()).isFalse();
		assertThat(limiter.hit(policy, "b@example.com").allowed()).isTrue();
		assertThat(limiter.hit(other, "a@example.com").allowed()).isTrue();
	}

	@Test
	void counterResetsWhenTheWindowRollsOver() throws Exception {
		JdbcRateLimiter limiter = newInstance();
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 2, Duration.ofMinutes(15));

		assertThat(limiter.hit(policy, "x").allowed()).isTrue();
		assertThat(limiter.hit(policy, "x").allowed()).isTrue();
		assertThat(limiter.hit(policy, "x").allowed()).isFalse();

		clock.advance(Duration.ofMinutes(15));

		RateLimitResult result = limiter.hit(policy, "x");
		assertThat(result.allowed()).isTrue();
		assertThat(result.count()).isEqualTo(1);
	}

	@Test
	void limitHoldsAcrossTwoApplicationInstancesUnderConcurrency() throws Exception {
		JdbcRateLimiter first = newInstance();
		JdbcRateLimiter second = newInstance();
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 25, Duration.ofHours(1));
		int threads = 16;
		int hitsPerThread = 10;

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				JdbcRateLimiter limiter = t % 2 == 0 ? first : second;
				Callable<Integer> task = () -> {
					int allowed = 0;
					for (int i = 0; i < hitsPerThread; i++) {
						if (limiter.hit(policy, "shared-key").allowed()) {
							allowed++;
						}
					}
					return allowed;
				};
				futures.add(executor.submit(task));
			}
			int totalAllowed = 0;
			for (Future<Integer> future : futures) {
				totalAllowed += future.get();
			}
			assertThat(totalAllowed).as("hits allowed across both instances").isEqualTo(25);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void storedKeysAreHashedAndNeverContainTheIdentifier() throws Exception {
		JdbcRateLimiter limiter = newInstance();
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 5, Duration.ofHours(1));
		String email = "person." + UUID.randomUUID() + "@example.com";

		limiter.hit(policy, email);

		List<String> hashes = jdbc.queryForList("SELECT key_hash FROM rate_limit_counter WHERE policy = ?",
				String.class, policyName);
		assertThat(hashes).hasSize(1);
		assertThat(hashes.get(0)).matches("[0-9a-f]{64}").doesNotContain("example.com");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_counter WHERE key_hash LIKE ?", Integer.class,
				"%" + email + "%")).isZero();
	}

	@Test
	void storeFailureFailsClosedQuickly() throws Exception {
		RateLimitStore broken = new RateLimitStore("jdbc:postgresql://127.0.0.1:1/none", "nobody", "nothing",
				properties);
		stores.add(broken);
		JdbcRateLimiter limiter = new JdbcRateLimiter(broken, new KeyHasher(SECRET), clock);
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 5, Duration.ofHours(1));

		long started = System.nanoTime();
		assertThatThrownBy(() -> limiter.hit(policy, "x")).isInstanceOf(RateLimitStoreUnavailableException.class);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

		assertThat(elapsed).as("fails within the connection timeout, not after a long hang")
			.isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void sweepRemovesOnlyExpiredCounters() throws Exception {
		JdbcRateLimiter limiter = newInstance();
		RateLimitPolicy policy = new RateLimitPolicy(policyName, 5, Duration.ofMinutes(1));

		limiter.hit(policy, "old");
		clock.advance(Duration.ofMinutes(5));
		limiter.hit(policy, "current");

		assertThat(limiter.sweepExpired()).isGreaterThanOrEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_counter WHERE policy = ?", Integer.class,
				policyName)).isEqualTo(1);
	}

	@Test
	void databaseRejectsAZeroHitCount() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO rate_limit_counter (policy, key_hash, window_start, hit_count, expires_at)
				VALUES (?, 'k', now(), 0, now() + interval '1 hour')
				""", policyName)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void propertiesRequireALongHmacKey() {
		assertThatThrownBy(() -> new RateLimitProperties("short", 4, Duration.ofMillis(250), Duration.ofSeconds(1),
				Duration.ofMinutes(5))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new RateLimitProperties(null, 4, Duration.ofMillis(250), Duration.ofSeconds(1),
				Duration.ofMinutes(5))).isInstanceOf(IllegalStateException.class);
	}

	/** A clock the test can move forward. */
	static final class MutableClock extends Clock {

		private final AtomicReference<Instant> now;

		MutableClock(Instant start) {
			this.now = new AtomicReference<>(start);
		}

		void advance(Duration duration) {
			now.updateAndGet(current -> current.plus(duration));
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}

	}

}
