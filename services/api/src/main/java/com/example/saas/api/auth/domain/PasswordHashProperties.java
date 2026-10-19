package com.example.saas.api.auth.domain;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Argon2id parameters and the cap on concurrent hashing (D-12, D-85).
 *
 * <p>The defaults are the OWASP minimum (19 MiB, 2 iterations, 1 lane). They are provisional: the final
 * values come from benchmarking on the production task size (about 250 to 500 ms per hash) and need
 * security approval. Parameters are stored in each hash, so they can be raised later and are upgraded at
 * the next login.
 *
 * @param memoryKib memory per hash in KiB
 * @param iterations time cost
 * @param parallelism lanes
 * @param maxConcurrent how many hashes may run at once, so a login flood cannot exhaust memory
 * @param waitTimeout how long a request waits for a free slot before it is refused with 503
 */
@ConfigurationProperties("app.security.password-hash")
public record PasswordHashProperties(
		@DefaultValue("19456") int memoryKib,
		@DefaultValue("2") int iterations,
		@DefaultValue("1") int parallelism,
		@DefaultValue("4") int maxConcurrent,
		@DefaultValue("2s") Duration waitTimeout) {

	public PasswordHashProperties {
		if (memoryKib < 8 * parallelism || iterations < 1 || parallelism < 1 || maxConcurrent < 1) {
			throw new IllegalStateException("Invalid app.security.password-hash settings");
		}
	}

}
