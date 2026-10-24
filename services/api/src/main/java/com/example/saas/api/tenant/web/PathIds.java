package com.example.saas.api.tenant.web;

import java.util.UUID;
import java.util.regex.Pattern;

import com.example.saas.api.tenant.application.CompanyNotFoundException;

/**
 * IDs taken from a path. Only the canonical 36-character form is accepted, because {@code UUID.fromString} also reads
 * shortened forms such as {@code 1-1-1-1-1}, which would give one resource many URLs. Anything else is "not found",
 * like an unknown ID, so a malformed path reveals nothing.
 */
public final class PathIds {

	private static final Pattern CANONICAL_UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private PathIds() {
	}

	public static UUID parse(String raw) {
		if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
			throw new CompanyNotFoundException();
		}
		return UUID.fromString(raw);
	}

}
