package com.example.saas.api.security;

/** The session is valid but its last login or step-up is too old for this operation. */
public class ReauthenticationRequiredException extends RuntimeException {

	public ReauthenticationRequiredException() {
		super(null, null, false, false);
	}

}
