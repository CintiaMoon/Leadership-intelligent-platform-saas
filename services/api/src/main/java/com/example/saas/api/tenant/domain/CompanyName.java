package com.example.saas.api.tenant.domain;

import java.text.Normalizer;
import java.util.Optional;

/** The company-name rule (D-105): 1 to 100 code points after NFKC and trim, no control characters. */
public final class CompanyName {

	public static final int MAX_CODE_POINTS = 100;

	/** Matches the contract's wire limit; checked before normalization so a huge input costs nothing. */
	public static final int MAX_RAW_CHARS = 400;

	private CompanyName() {
	}

	/** The normalized name, or empty if the input breaks the rule. */
	public static Optional<String> normalize(String raw) {
		if (raw == null || raw.length() > MAX_RAW_CHARS) {
			return Optional.empty();
		}
		String name = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
		int length = name.codePointCount(0, name.length());
		if (length < 1 || length > MAX_CODE_POINTS) {
			return Optional.empty();
		}
		if (name.codePoints().anyMatch(Character::isISOControl)) {
			return Optional.empty();
		}
		return Optional.of(name);
	}

}
