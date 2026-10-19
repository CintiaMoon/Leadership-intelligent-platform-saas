package com.example.saas.api.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Signup input. The password is bounded here only to cap request size; the password policy decides the rest. */
record SignupRequest(
		@NotNull @Size(max = 254) String email,
		@NotNull @Size(max = 1024) String password,
		@NotNull @Size(max = 400) String displayName) {

	@Override
	public String toString() {
		return "SignupRequest[redacted]";
	}

}
