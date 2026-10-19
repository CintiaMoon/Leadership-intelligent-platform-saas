package com.example.saas.api.auth.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing with a cap on concurrent operations (SPEC-AUTH, D-12, D-85).
 *
 * <p>Every hash and verification needs a permit, so a flood of login or signup requests queues briefly and
 * is then refused with {@link PasswordHashingBusyException} instead of exhausting memory (each operation
 * allocates {@code memoryKib}). Callers pass the NFKC-normalized password ({@link PasswordPolicy#normalize}).
 */
@Component
public class PasswordHasher {

	private static final int SALT_LENGTH = 16;

	private static final int HASH_LENGTH = 32;

	/** Matches the parameter block of a PHC string: $argon2id$v=19$m=19456,t=2,p=1$salt$hash */
	private static final Pattern PARAMETERS = Pattern.compile("^\\$argon2id\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$");

	private final PasswordHashProperties properties;

	private final Argon2PasswordEncoder encoder;

	private final Semaphore permits;

	private volatile String dummyHash;

	public PasswordHasher(PasswordHashProperties properties) {
		this.properties = properties;
		this.encoder = new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, properties.parallelism(),
				properties.memoryKib(), properties.iterations());
		this.permits = new Semaphore(properties.maxConcurrent(), true);
	}

	/** Hashes with the current parameters; the parameters are recorded inside the returned string. */
	public String hash(String normalizedPassword) {
		return withPermit(() -> encoder.encode(normalizedPassword));
	}

	public boolean matches(String normalizedPassword, String storedHash) {
		return withPermit(() -> encoder.matches(normalizedPassword, storedHash));
	}

	/**
	 * Runs a verification against a hash nobody knows the password of. Used when the account does not exist
	 * or cannot log in, so the response takes about as long as a real check (AC-AUTH-13, 19, 29).
	 */
	public void verifyDummy(String normalizedPassword) {
		String hash = dummyHash;
		if (hash == null) {
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			hash = hash(Base64.getEncoder().encodeToString(random));
			dummyHash = hash;
		}
		matches(normalizedPassword, hash);
	}

	/** True if the stored hash was made with weaker parameters than the current ones (AC-AUTH-27). */
	public boolean needsUpgrade(String storedHash) {
		Matcher matcher = PARAMETERS.matcher(storedHash);
		if (!matcher.find()) {
			return true;
		}
		int memory = Integer.parseInt(matcher.group(1));
		int iterations = Integer.parseInt(matcher.group(2));
		int parallelism = Integer.parseInt(matcher.group(3));
		return memory < properties.memoryKib() || iterations < properties.iterations()
				|| parallelism != properties.parallelism();
	}

	private <T> T withPermit(java.util.function.Supplier<T> work) {
		boolean acquired;
		try {
			acquired = permits.tryAcquire(properties.waitTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PasswordHashingBusyException();
		}
		if (!acquired) {
			throw new PasswordHashingBusyException();
		}
		try {
			return work.get();
		}
		finally {
			permits.release();
		}
	}

}
