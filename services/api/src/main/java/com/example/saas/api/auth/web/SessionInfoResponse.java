package com.example.saas.api.auth.web;

import java.time.Instant;
import java.util.UUID;

/** The signed-in user. Never carries the session identifier, which travels only in the cookie. */
record SessionInfoResponse(UUID userId, String email, String displayName, boolean emailVerified,
		Instant lastAuthenticatedAt, Instant expiresAt) {
}
