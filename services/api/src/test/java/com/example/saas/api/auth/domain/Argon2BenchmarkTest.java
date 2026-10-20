package com.example.saas.api.auth.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Argon2id benchmark for the D-12 decision. Not part of the normal build: run it on the production task size with
 * {@code ./mvnw test -Dtest=Argon2BenchmarkTest -Dbenchmark=true} and record the output for security approval.
 * The numbers depend on the machine, so results from a developer laptop are not the approved values.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class Argon2BenchmarkTest {

	private record Candidate(int memoryKib, int iterations, int parallelism) {
	}

	private static final List<Candidate> CANDIDATES = List.of(new Candidate(19456, 2, 1), new Candidate(19456, 3, 1),
			new Candidate(12288, 3, 1), new Candidate(9216, 4, 1), new Candidate(46080, 1, 1),
			new Candidate(47104, 1, 1), new Candidate(65536, 2, 1), new Candidate(65536, 3, 1),
			new Candidate(65536, 4, 1), new Candidate(131072, 2, 1));

	private static final int[] CONCURRENCY = { 1, 2, 4, 8 };

	private static final int WARMUP = 5;

	private static final int SAMPLES = 20;

	@Test
	void report() throws Exception {
		Runtime runtime = Runtime.getRuntime();
		System.out.printf("BENCHMARK java=%s cpus=%d maxHeapMiB=%d%n", System.getProperty("java.version"),
				runtime.availableProcessors(), runtime.maxMemory() / (1024 * 1024));
		System.out.println("BENCHMARK memKiB,iter,lanes,concurrency,p50ms,p95ms,maxMs,hashesPerSec");
		for (Candidate candidate : CANDIDATES) {
			PasswordHasher hasher = new PasswordHasher(new PasswordHashProperties(candidate.memoryKib(),
					candidate.iterations(), candidate.parallelism(), 64, Duration.ofSeconds(60)));
			for (int i = 0; i < WARMUP; i++) {
				hasher.hash("warm-up-password-" + i);
			}
			for (int concurrency : CONCURRENCY) {
				measure(candidate, hasher, concurrency);
			}
		}
	}

	private static void measure(Candidate candidate, PasswordHasher hasher, int concurrency) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(concurrency);
		try {
			int perThread = Math.max(SAMPLES / concurrency, 5);
			List<Callable<long[]>> jobs = new ArrayList<>();
			for (int t = 0; t < concurrency; t++) {
				jobs.add(() -> {
					long[] nanos = new long[perThread];
					for (int i = 0; i < perThread; i++) {
						long start = System.nanoTime();
						hasher.hash("benchmark-password-value");
						nanos[i] = System.nanoTime() - start;
					}
					return nanos;
				});
			}
			long wallStart = System.nanoTime();
			List<Future<long[]>> futures = pool.invokeAll(jobs);
			long wall = System.nanoTime() - wallStart;
			List<Long> all = new ArrayList<>();
			for (Future<long[]> future : futures) {
				for (long value : future.get()) {
					all.add(value);
				}
			}
			long[] sorted = all.stream().mapToLong(Long::longValue).sorted().toArray();
			double hashesPerSecond = sorted.length / (wall / 1e9);
			System.out.printf("BENCHMARK %d,%d,%d,%d,%.0f,%.0f,%.0f,%.1f%n", candidate.memoryKib(),
					candidate.iterations(), candidate.parallelism(), concurrency, percentile(sorted, 0.50),
					percentile(sorted, 0.95), Arrays.stream(sorted).max().orElse(0) / 1e6, hashesPerSecond);
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static double percentile(long[] sorted, double fraction) {
		int index = (int) Math.min(sorted.length - 1, Math.ceil(fraction * sorted.length) - 1);
		return sorted[Math.max(index, 0)] / 1e6;
	}

}
