package com.example.saas.api.security;

/** Names of the attributes stored in the server-side session. */
public final class SessionAttributes {

	/** The user ID as text. Also the Spring Session principal name, so all of a user's sessions can be found. */
	public static final String USER_ID = "auth.userId";

	/** Epoch milliseconds of the last login or step-up. */
	public static final String LAST_AUTHENTICATED_AT = "auth.lastAuthenticatedAt";

	private SessionAttributes() {
	}

}
