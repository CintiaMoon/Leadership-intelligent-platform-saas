package com.example.saas.api.auth.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The password rules (SPEC-AUTH, D-76, D-77, AC-AUTH-17, AC-AUTH-38): 8 to 128 code points, any characters,
 * no composition rules, and not on the bundled common/compromised-password list. Input is Unicode
 * NFKC-normalized first, so visually equivalent forms are the same password; it is never trimmed or
 * truncated. The normalized value is what gets hashed.
 */
@Component
public class PasswordPolicy {

	public static final int MIN_LENGTH = 8;

	public static final int MAX_LENGTH = 128;

	/** Why a password was refused. The codes are the stable field-error codes returned by the API. */
	public enum Violation {

		TOO_SHORT, TOO_LONG, BREACHED_PASSWORD

	}

	private static final String BLOCKLIST_RESOURCE = "auth/common-passwords.txt";

	private final Set<String> blocklist;

	public PasswordPolicy() {
		this.blocklist = loadBlocklist(BLOCKLIST_RESOURCE);
	}

	/** The form that is checked and hashed. */
	public static String normalize(String password) {
		return Normalizer.normalize(password, Normalizer.Form.NFKC);
	}

	/** Returns the first violated rule, or empty if the password is acceptable. */
	public Optional<Violation> check(String password) {
		String normalized = normalize(password);
		int length = normalized.codePointCount(0, normalized.length());
		if (length < MIN_LENGTH) {
			return Optional.of(Violation.TOO_SHORT);
		}
		if (length > MAX_LENGTH) {
			return Optional.of(Violation.TOO_LONG);
		}
		if (blocklist.contains(normalized.toLowerCase(Locale.ROOT))) {
			return Optional.of(Violation.BREACHED_PASSWORD);
		}
		return Optional.empty();
	}

	private static Set<String> loadBlocklist(String resource) {
		Set<String> words = new HashSet<>();
		try (InputStream in = new ClassPathResource(resource).getInputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isEmpty()) {
					words.add(line);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot load the common-password list " + resource, ex);
		}
		if (words.isEmpty()) {
			throw new IllegalStateException("The common-password list " + resource + " is empty");
		}
		return Set.copyOf(words);
	}

}
