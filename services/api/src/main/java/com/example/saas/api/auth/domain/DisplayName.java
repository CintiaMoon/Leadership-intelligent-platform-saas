package com.example.saas.api.auth.domain;

import java.util.Optional;

/** A user's display name (D-87): 1 to 100 code points after trimming, no control characters. */
public final class DisplayName {

	public static final int MAX_LENGTH = 100;

	private DisplayName() {
	}

	/** Returns the trimmed name, or empty if it is blank, too long, or contains control characters. */
	public static Optional<String> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String name = raw.strip();
		int length = name.codePointCount(0, name.length());
		if (length < 1 || length > MAX_LENGTH) {
			return Optional.empty();
		}
		boolean hasControl = name.codePoints()
			.anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT);
		return hasControl ? Optional.empty() : Optional.of(name);
	}

}
