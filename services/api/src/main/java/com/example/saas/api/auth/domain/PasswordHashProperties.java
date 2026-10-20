package com.example.saas.api.auth.domain;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Argon2id parameters and the cap on concurrent hashing (D-12, D-85).
 *
 * <p>The defaults (64 MiB, 4 iterations, 1 lane, 2 concurrent hashes) were approved in D-96 from a benchmark on
 * an assumed 1 vCPU / 2 GiB task: about 245 ms per hash, 4 hashes a second. Re-measure on the real task size
 * and set {@code app.security.password-hash.*} if it differs; with 2 vCPUs the concurrency can rise to 4.
 * Parameters are stored in each hash, so they can be changed later and are upgraded at the next login.
 *
 * @param memoryKib memory per hash in KiB
 * @param iterations time cost
 * @param parallelism lanes
 * @param maxConcurrent how many hashes may run at once, so a login flood cannot exhaust memory
 * @param waitTimeout how long a request waits for a free slot before it is refused with 503
 */
@ConfigurationProperties("app.security.password-hash")
public record PasswordHashProperties(
		@DefaultValue("65536") int memoryKib,
		@DefaultValue("4") int iterations,
		@DefaultValue("1") int parallelism,
		@DefaultValue("2") int maxConcurrent,
		@DefaultValue("2s") Duration waitTimeout) {

	public PasswordHashProperties {
		if (memoryKib < 8 * parallelism || iterations < 1 || parallelism < 1 || maxConcurrent < 1) {
			throw new IllegalStateException("Invalid app.security.password-hash settings");
		}
	}

}
