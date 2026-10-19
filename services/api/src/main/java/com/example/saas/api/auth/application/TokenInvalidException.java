package com.example.saas.api.auth.application;

/** The token is unknown, expired, or already consumed. One exception for all three: no oracle (AC-AUTH-37). */
public class TokenInvalidException extends RuntimeException {

	public TokenInvalidException() {
		super("Token invalid");
	}

}
