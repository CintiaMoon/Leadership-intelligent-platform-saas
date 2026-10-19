package com.example.saas.api.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Both secrets travel in the body, never in the path or query string (AC-AUTH-35). */
record PasswordResetConfirmRequest(
		@NotNull @Size(max = 128) String token,
		@NotNull @Size(max = 1024) String newPassword) {

	@Override
	public String toString() {
		return "PasswordResetConfirmRequest[redacted]";
	}

}
