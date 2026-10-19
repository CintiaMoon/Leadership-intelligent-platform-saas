package com.example.saas.api.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A one-time token in the body, never in the path or query string (D-44, AC-AUTH-35). */
record TokenRequest(@NotNull @Size(max = 128) String token) {

	@Override
	public String toString() {
		return "TokenRequest[redacted]";
	}

}
