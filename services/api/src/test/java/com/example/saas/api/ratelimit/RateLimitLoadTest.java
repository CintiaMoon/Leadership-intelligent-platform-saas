package com.example.saas.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.example.saas.api.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load test of the PostgreSQL counter store (the ADR-004 release gate). Not part of the normal build:
 * {@code ./mvnw test -Dtest=RateLimitLoadTest -Dbenchmark=true}. It reports throughput and latency for a hot key
 * and for many keys, shows what a flood does to a deliberately small pool, and checks that the limit holds exactly
 * under contention. The database is a Testcontainers PostgreSQL on the same machine, so absolute numbers are a
 * lower bound on network latency; re-run against the production database class before release.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class RateLimitLoadTest {

	private static final String SECRET = "test-rate-limit-hmac-key-0123456789abcdef0123456789abcdef";

	private static final int THREADS = 64;

	private static final int HITS_PER_THREAD = 400;

	@Autowired
	DataSource dataSource;

	@Autowired
	RateLimitProperties properties;

	private JdbcRateLimiter limiter(int poolSize, Duration connectionTimeout) throws Exception {
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		RateLimitProperties sized = new RateLimitProperties(properties.hmacKey(), poolSize, connectionTimeout,
				properties.queryTimeout(), properties.sweepInterval());
		return new JdbcRateLimiter(
				new RateLimitStore(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword(), sized),
				new KeyHasher(SECRET), Clock.systemUTC());
	}

	private record Outcome(long[] nanos, int allowed, int refused, int unavailable) {
	}

	private Outcome run(JdbcRateLimiter limiter, RateLimitPolicy policy, int threads, int hitsPerThread,
			boolean hotKey) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		AtomicInteger allowed = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		AtomicInteger unavailable = new AtomicInteger();
		try {
			List<Callable<long[]>> jobs = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				jobs.add(() -> {
					long[] nanos = new long[hitsPerThread];
					for (int i = 0; i < hitsPerThread; i++) {
						String key = hotKey ? "hot@example.com" : UUID.randomUUID().toString();
						long start = System.nanoTime();
						try {
							if (limiter.hit(policy, key).allowed()) {
								allowed.incrementAndGet();
							}
							else {
								refused.incrementAndGet();
							}
						}
						catch (RateLimitStoreUnavailableException ex) {
							unavailable.incrementAndGet();
						}
						nanos[i] = System.nanoTime() - start;
					}
					return nanos;
				});
			}
			List<long[]> all = new ArrayList<>();
			for (Future<long[]> future : pool.invokeAll(jobs)) {
				all.add(future.get());
			}
			return new Outcome(all.stream().flatMapToLong(java.util.Arrays::stream).sorted().toArray(), allowed.get(),
					refused.get(), unavailable.get());
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void print(String label, Outcome outcome, long wallNanos) {
		long[] n = outcome.nanos();
		System.out.printf("LOAD %-34s hits=%d allowed=%d refused=%d unavailable=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.1fms rate=%.0f/s%n",
				label, n.length, outcome.allowed(), outcome.refused(), outcome.unavailable(), n[n.length / 2] / 1e6,
				n[(int) (n.length * 0.95)] / 1e6, n[(int) (n.length * 0.99)] / 1e6, n[n.length - 1] / 1e6,
				n.length / (wallNanos / 1e9));
	}

	@Test
	void report() throws Exception {
		System.out.printf("LOAD cpus=%d threads=%d%n", Runtime.getRuntime().availableProcessors(), THREADS);

		// Default pool (4): the production configuration.
		JdbcRateLimiter standard = limiter(4, Duration.ofMillis(250));
		long start = System.nanoTime();
		Outcome many = run(standard, new RateLimitPolicy("load-many-" + UUID.randomUUID(), 1_000_000, Duration.ofHours(1)),
				THREADS, HITS_PER_THREAD, false);
		print("many keys, pool 4", many, System.nanoTime() - start);

		// One hot key with a limit of 100: the limit must hold exactly, and refusals past it must be cheap.
		RateLimitPolicy hot = new RateLimitPolicy("load-hot-" + UUID.randomUUID(), 100, Duration.ofHours(1));
		start = System.nanoTime();
		Outcome contended = run(standard, hot, THREADS, HITS_PER_THREAD, true);
		print("one hot key, limit 100, pool 4", contended, System.nanoTime() - start);
		assertThat(contended.allowed()).as("exactly the limit is allowed under contention").isEqualTo(100);
		assertThat(contended.unavailable()).isZero();

		// Same hot key, but spread over 8 distinct limiter instances (stand-in for 8 app instances; the in-memory
		// short-cut is per instance, so the database alone enforces the limit across them).
		RateLimitPolicy shared = new RateLimitPolicy("load-shared-" + UUID.randomUUID(), 100, Duration.ofHours(1));
		List<JdbcRateLimiter> instances = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			instances.add(limiter(4, Duration.ofMillis(250)));
		}
		ExecutorService pool = Executors.newFixedThreadPool(32);
		AtomicInteger allowed = new AtomicInteger();
		List<Future<?>> futures = new ArrayList<>();
		start = System.nanoTime();
		for (int t = 0; t < 32; t++) {
			JdbcRateLimiter instance = instances.get(t % 8);
			futures.add(pool.submit(() -> {
				for (int i = 0; i < 100; i++) {
					if (instance.hit(shared, "shared@example.com").allowed()) {
						allowed.incrementAndGet();
					}
				}
			}));
		}
		for (Future<?> future : futures) {
			future.get();
		}
		pool.shutdownNow();
		System.out.printf("LOAD %-34s hits=3200 allowed=%d (limit 100) wall=%.0fms%n", "hot key across 8 instances",
				allowed.get(), (System.nanoTime() - start) / 1e6);
		assertThat(allowed.get()).isEqualTo(100);

		// A pool of one with the shortest allowed wait: shows the fail-closed path under saturation.
		JdbcRateLimiter tiny = limiter(1, Duration.ofMillis(250));
		start = System.nanoTime();
		Outcome saturated = run(tiny, new RateLimitPolicy("load-tiny-" + UUID.randomUUID(), 1_000_000, Duration.ofHours(1)),
				THREADS, 100, false);
		print("saturated pool 1, 64 threads", saturated, System.nanoTime() - start);

		// Cleanup cost of a large expired backlog.
		new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("""
				INSERT INTO rate_limit_counter (policy, key_hash, window_start, hit_count, expires_at)
				SELECT 'load-expired', 'k' || g, now() - interval '2 days', 1, now() - interval '1 day'
				FROM generate_series(1, 200000) g
				""");
		JdbcRateLimiter sweeper = limiter(4, Duration.ofMillis(250));
		long sweepStart = System.nanoTime();
		int removed = sweeper.sweepExpired();
		System.out.printf("LOAD sweep of expired rows removed=%d in %.0fms%n", removed,
				(System.nanoTime() - sweepStart) / 1e6);
	}

}
