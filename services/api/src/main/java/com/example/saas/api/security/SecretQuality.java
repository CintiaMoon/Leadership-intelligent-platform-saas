package com.example.saas.api.security;

import java.util.List;
import java.util.Locale;

/**
 * Refuses secrets that are obviously not secret: sample values copied from documentation or an {@code .env} template,
 * and strings with almost no variety. It cannot prove a value is random; it catches the mistake of deploying the
 * placeholder.
 */
public final class SecretQuality {

	private static final List<String> PLACEHOLDER_MARKERS = List.of("changeme", "change_me", "change-me",
			"should_change", "should-change", "placeholder", "replace_me", "replace-me", "your_secret", "your-secret",
			"insert", "todo", "secret_here", "secret-here", "dummy", "sample");

	/** Values that were committed to the repository once (history keeps them), so they are public and never acceptable. */
	private static final List<String> PUBLISHED_VALUES = List
		.of("vQ8mZ2xKp7N4aR1sT9wE3yH6uJ0cL5dF8gB2nM4xA7kP1rS6tV9zQ3eW5hY0iO2l");

	static final int MIN_DISTINCT_CHARACTERS = 12;

	private SecretQuality() {
	}

	/** @throws IllegalStateException the value looks like a placeholder or has too little variety */
	public static void requireNotPlaceholder(String settingName, String value) {
		if (PUBLISHED_VALUES.contains(value)) {
			throw new IllegalStateException(settingName + " is a value that was published in the repository. "
					+ "Generate a real value with: openssl rand -base64 48");
		}
		String lower = value.toLowerCase(Locale.ROOT);
		for (String marker : PLACEHOLDER_MARKERS) {
			if (lower.contains(marker)) {
				throw new IllegalStateException(settingName + " looks like a placeholder (contains \"" + marker
						+ "\"). Generate a real value with: openssl rand -base64 48");
			}
		}
		if (value.chars().distinct().count() < MIN_DISTINCT_CHARACTERS) {
			throw new IllegalStateException(settingName + " has too little variety to be a random secret. "
					+ "Generate a real value with: openssl rand -base64 48");
		}
	}

}
