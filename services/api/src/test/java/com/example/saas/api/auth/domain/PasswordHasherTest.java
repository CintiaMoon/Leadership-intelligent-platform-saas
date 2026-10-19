package com.example.saas.api.auth.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-AUTH-27 (parameters in the hash, upgrade detection) and AC-AUTH-28/39 (concurrency cap). */
class PasswordHasherTest {

	private static PasswordHasher hasher(int memory, int iterations, int concurrent, Duration wait) {
		return new PasswordHasher(new PasswordHashProperties(memory, iterations, 1, concurrent, wait));
	}

	@Test
	void hashesWithArgon2idAndRecordsTheParametersInTheHash() {
		PasswordHasher hasher = hasher(19456, 2, 4, Duration.ofSeconds(5));

		String hash = hasher.hash("correct horse battery staple");

		assertThat(hash).startsWith("$argon2id$").contains("m=19456,t=2,p=1");
		assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
		assertThat(hasher.matches("wrong horse battery staple", hash)).isFalse();
	}

	@Test
	void samePasswordGivesDifferentHashes() {
		PasswordHasher hasher = hasher(512, 1, 4, Duration.ofSeconds(5));

		assertThat(hasher.hash("same password")).isNotEqualTo(hasher.hash("same password"));
	}

	@Test
	void hashMadeWithWeakerParametersNeedsAnUpgrade() {
		String weak = hasher(512, 1, 4, Duration.ofSeconds(5)).hash("some password value");
		PasswordHasher current = hasher(1024, 2, 4, Duration.ofSeconds(5));

		assertThat(current.needsUpgrade(weak)).isTrue();
		assertThat(current.needsUpgrade(current.hash("some password value"))).isFalse();
		assertThat(current.needsUpgrade("not-a-phc-string")).isTrue();
	}

	@Test
	void dummyVerificationNeverThrows() {
		PasswordHasher hasher = hasher(512, 1, 4, Duration.ofSeconds(5));

		hasher.verifyDummy("anything at all");
		hasher.verifyDummy("anything at all");
	}

	@Test
	void whenAllSlotsAreBusyTheRequestIsRefusedWithinTheWaitLimit() throws Exception {
		// One slot and a slow hash: a second caller must be refused after the wait limit, not queued for long.
		PasswordHasher hasher = hasher(131072, 8, 1, Duration.ofMillis(50));
		CountDownLatch started = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> {
				started.countDown();
				hasher.hash("first caller password");
			});
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			Thread.sleep(20);

			long begin = System.nanoTime();
			assertThatThrownBy(() -> hasher.hash("second caller password")).isInstanceOf(PasswordHashingBusyException.class);
			assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(2));

			first.get(60, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void slotsAreReleasedAfterUseSoLaterCallsSucceed() throws Exception {
		PasswordHasher hasher = hasher(512, 1, 2, Duration.ofSeconds(30));
		ExecutorService executor = Executors.newFixedThreadPool(6);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				futures.add(executor.submit(() -> hasher.hash("password number one")));
			}
			for (Future<String> future : futures) {
				assertThat(future.get(60, TimeUnit.SECONDS)).startsWith("$argon2id$");
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

}
