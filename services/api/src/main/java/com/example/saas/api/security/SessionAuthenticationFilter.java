package com.example.saas.api.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid server-side session into the request's authentication (AC-AUTH-07, 15, 16; invariant 10).
 *
 * <p>The idle timeout is applied by Spring Session: an idle session is not returned at all. This filter adds the
 * rules the framework lacks. The session must be younger than the absolute lifetime, however active it was,
 * and the account must still exist, be ACTIVE, and have a verified email. A session that fails either rule is
 * destroyed, so revocation takes effect on the next request. Identity is read only from the session.
 *
 * <p>Created by the security configuration and intentionally not a bean.
 */
public final class SessionAuthenticationFilter extends OncePerRequestFilter {

	private final UserAccountRepository users;

	private final SessionProperties properties;

	private final Clock clock;

	private final RequestAttributeSecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

	public SessionAuthenticationFilter(UserAccountRepository users, SessionProperties properties, Clock clock) {
		this.users = users;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !RequestPaths.of(request).startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			AuthenticatedUser user = authenticate(session);
			if (user != null) {
				SecurityContext context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(new SessionAuthentication(user));
				SecurityContextHolder.setContext(context);
				// Tell Spring Security that this request already has a context. Without this its session management
				// treats the authentication as a new login and rotates the session identifier and CSRF token on
				// every single request.
				contextRepository.saveContext(context, request, response);
			}
		}
		chain.doFilter(request, response);
	}

	private AuthenticatedUser authenticate(HttpSession session) {
		Object userId = session.getAttribute(SessionAttributes.USER_ID);
		Object lastAuthenticated = session.getAttribute(SessionAttributes.LAST_AUTHENTICATED_AT);
		Object version = session.getAttribute(SessionAttributes.CREDENTIALS_VERSION);
		if (!(userId instanceof String id) || !(lastAuthenticated instanceof Long lastMillis)
				|| !(version instanceof Long credentialsVersion)) {
			return null;
		}
		Instant now = clock.instant();
		Instant expiresAt = Instant.ofEpochMilli(session.getCreationTime()).plus(properties.absoluteLifetime());
		if (!now.isBefore(expiresAt)) {
			session.invalidate();
			return null;
		}
		UUID uuid;
		try {
			uuid = UUID.fromString(id);
		}
		catch (IllegalArgumentException ex) {
			session.invalidate();
			return null;
		}
		if (!users.existsByIdAndStatusAndEmailVerifiedAtIsNotNullAndCredentialsVersion(uuid, UserAccount.Status.ACTIVE,
				credentialsVersion)) {
			session.invalidate();
			return null;
		}
		return new AuthenticatedUser(uuid, Instant.ofEpochMilli(lastMillis), expiresAt);
	}

}
