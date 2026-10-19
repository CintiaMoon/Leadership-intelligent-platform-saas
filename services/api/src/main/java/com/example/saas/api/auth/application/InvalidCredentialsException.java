package com.example.saas.api.auth.application;

/**
 * Login or step-up failed. One exception, one response, for every reason: unknown account, wrong password,
 * unverified email, DISABLED account, and throttled attempts (AC-AUTH-13, 19, 26, 29).
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid credentials");
	}

}
