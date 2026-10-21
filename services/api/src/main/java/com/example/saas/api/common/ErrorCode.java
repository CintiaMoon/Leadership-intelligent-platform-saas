package com.example.saas.api.common;

import org.springframework.http.HttpStatus;

/**
 * Stable application error codes (ADR-005). Keep in step with the {@code ErrorCode} enum in
 * contracts/openapi.yaml; the frontend maps these to user-facing messages.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is invalid."),
	REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request could not be processed."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The email or password is incorrect."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "The request is not permitted."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "The resource was not found."),
	CSRF_REJECTED(HttpStatus.FORBIDDEN, "The request was rejected."),
	REAUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN, "Confirm your password to continue."),
	TOKEN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "The link is invalid or has expired."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Try again later."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

	private final HttpStatus status;

	private final String detail;

	ErrorCode(HttpStatus status, String detail) {
		this.status = status;
		this.detail = detail;
	}

	public HttpStatus status() {
		return status;
	}

	/** Fixed, user-safe text. Never built from request data or exception messages. */
	public String detail() {
		return detail;
	}

	/** The RFC 9457 {@code type} URI reference for this code. */
	public String type() {
		return "urn:saas:problem:" + name().toLowerCase().replace('_', '-');
	}

}
