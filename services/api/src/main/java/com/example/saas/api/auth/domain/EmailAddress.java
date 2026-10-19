package com.example.saas.api.auth.domain;

import java.net.IDN;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An email address in the form used for uniqueness, login, and invitation matching (D-55, AC-AUTH-11).
 *
 * <p>Normalization: trim, lowercase the whole address, convert the domain to its ASCII (punycode) form.
 * Plus-tags and dots are not altered. Addresses longer than 254 characters (before or after the domain
 * conversion) or containing control characters are rejected. The trimmed original text is kept for display
 * and for the email that is sent.
 *
 * @param normalized the form stored as the unique key
 * @param original the address as the user typed it, trimmed
 */
public record EmailAddress(String normalized, String original) {

	public static final int MAX_LENGTH = 254;

	private static final int MAX_LOCAL_LENGTH = 64;

	/** Dot-separated atoms of letters, digits, and the RFC 5322 atext symbols; no quoted forms, no spaces. */
	private static final Pattern LOCAL_PART = Pattern
		.compile("^[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~-]+(\\.[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~-]+)*$");

	private static final Pattern DOMAIN_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

	/** Returns the normalized address, or empty if the text is not an acceptable email address. */
	public static Optional<EmailAddress> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String original = raw.strip();
		if (original.isEmpty() || original.length() > MAX_LENGTH || containsControlCharacter(original)) {
			return Optional.empty();
		}
		int at = original.lastIndexOf('@');
		if (at <= 0 || at == original.length() - 1) {
			return Optional.empty();
		}
		String local = original.substring(0, at).toLowerCase(Locale.ROOT);
		String domain = original.substring(at + 1).toLowerCase(Locale.ROOT);
		if (local.length() > MAX_LOCAL_LENGTH || !LOCAL_PART.matcher(local).matches()) {
			return Optional.empty();
		}
		String asciiDomain = toAsciiDomain(domain);
		if (asciiDomain == null) {
			return Optional.empty();
		}
		String normalized = local + "@" + asciiDomain;
		if (normalized.length() > MAX_LENGTH) {
			return Optional.empty();
		}
		return Optional.of(new EmailAddress(normalized, original));
	}

	private static boolean containsControlCharacter(String value) {
		return value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT);
	}

	private static String toAsciiDomain(String domain) {
		String ascii;
		try {
			ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		String[] labels = ascii.split("\\.", -1);
		if (labels.length < 2) {
			return null;
		}
		for (String label : labels) {
			if (!DOMAIN_LABEL.matcher(label).matches()) {
				return null;
			}
		}
		// A purely numeric last label would make the domain look like an IPv4 address.
		if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
			return null;
		}
		return ascii;
	}

}
