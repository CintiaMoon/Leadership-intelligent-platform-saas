package com.example.saas.api.invitation.web;

/**
 * The token from the invitation link, sent in the request body and nowhere else (D-44, AC-INV-18). It is never read
 * from the path or the query string; there is no route that could. {@code toString} is redacted so a log line or an
 * exception message that prints the request cannot leak it (AC-INV-07). Unknown fields are rejected while parsing.
 */
record AcceptInvitationRequest(String token) {

	@Override
	public String toString() {
		return "AcceptInvitationRequest[redacted]";
	}

}
