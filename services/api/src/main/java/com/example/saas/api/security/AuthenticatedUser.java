package com.example.saas.api.security;

import java.time.Instant;
import java.util.UUID;

/**
 * The user behind a valid session. Identity comes only from the server-side session, never from the request.
 *
 * @param lastAuthenticatedAt set only by login and step-up, never by activity (AC-AUTH-22)
 * @param sessionExpiresAt the absolute end of the session
 */
public record AuthenticatedUser(UUID userId, Instant lastAuthenticatedAt, Instant sessionExpiresAt) {
}
