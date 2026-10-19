package com.example.saas.api.auth.web;

/** The CSRF token for the caller. Redacted in toString so debug logging of the response body cannot leak it. */
record CsrfTokenResponse(String headerName, String token) {

	@Override
	public String toString() {
		return "CsrfTokenResponse[redacted]";
	}

}
