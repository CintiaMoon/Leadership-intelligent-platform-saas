package com.example.saas.api.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Login input. The password length is bounded only to cap request size; it is checked against the stored hash. */
record LoginRequest(
		@NotNull @Size(max = 254) String email,
		@NotNull @Size(max = 1024) String password) {

	@Override
	public String toString() {
		return "LoginRequest[redacted]";
	}

}
