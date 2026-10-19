package com.example.saas.api.auth.web;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.application.AuthenticatedAccount;
import com.example.saas.api.security.SessionAttributes;
import com.example.saas.api.security.SessionProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Opens, rotates, and ends the server-side session (AC-AUTH-05, 06, 22, 23; D-82).
 *
 * <p>Login never reuses an existing session: any session the browser sent is destroyed and a new one with a new
 * identifier is created, which defeats session fixation. The identifier is also rotated when the user
 * authenticates again (step-up) or when their own role or membership changes, and the CSRF token tied to the
 * old identifier is dropped so a new one is issued (D-75).
 */
@Component
public class SessionManager {

	private final Clock clock;

	private final SessionProperties properties;

	SessionManager(Clock clock, SessionProperties properties) {
		this.clock = clock;
		this.properties = properties;
	}

	SessionInfoResponse establish(HttpServletRequest request, AuthenticatedAccount account) {
		HttpSession existing = request.getSession(false);
		if (existing != null) {
			existing.invalidate();
		}
		HttpSession session = request.getSession(true);
		Instant now = clock.instant();
		String userId = account.id().toString();
		session.setAttribute(SessionAttributes.USER_ID, userId);
		session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId);
		session.setAttribute(SessionAttributes.LAST_AUTHENTICATED_AT, now.toEpochMilli());
		session.setAttribute(SessionAttributes.CREDENTIALS_VERSION, account.credentialsVersion());
		Instant expiresAt = Instant.ofEpochMilli(session.getCreationTime()).plus(properties.absoluteLifetime());
		return new SessionInfoResponse(account.id(), account.email(), account.displayName(), account.emailVerified(),
				now, expiresAt);
	}

	/** Records a fresh authentication (step-up) under a new session identifier and a new CSRF token. */
	public void reauthenticated(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return;
		}
		session.setAttribute(SessionAttributes.LAST_AUTHENTICATED_AT, clock.instant().toEpochMilli());
		rotate(request);
	}

	/** New session identifier, same session. Call after a change to the user's own role or membership. */
	public void rotate(HttpServletRequest request) {
		if (request.getSession(false) == null) {
			return;
		}
		request.changeSessionId();
		new HttpSessionCsrfTokenRepository().saveToken(null, request, null);
	}

	/** Destroys the session and returns the user it belonged to. */
	public Optional<UUID> end(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Optional.empty();
		}
		Object userId = session.getAttribute(SessionAttributes.USER_ID);
		session.invalidate();
		return userId instanceof String id ? Optional.of(UUID.fromString(id)) : Optional.empty();
	}

}
