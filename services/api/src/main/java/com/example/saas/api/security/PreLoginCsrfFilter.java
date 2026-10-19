package com.example.saas.api.security;

import java.io.IOException;
import java.util.Set;

import com.example.saas.api.common.ErrorCode;
import com.example.saas.api.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces CSRF protection on the state-changing endpoints that are used before a session exists (D-75,
 * AC-AUTH-09, AC-AUTH-36): the signed double-submit token must match its cookie, and a present
 * {@code Origin} must be one the BFF is allowed to send. CORS and SameSite are not relied upon.
 *
 * <p>Created by the security configuration and intentionally not a bean.
 */
public final class PreLoginCsrfFilter extends OncePerRequestFilter {

	/** POST paths that run before a session exists. Password reset joins in the next phase. */
	public static final Set<String> PATHS = Set.of("/api/v1/auth/signup", "/api/v1/auth/verify-email",
			"/api/v1/auth/verify-email/resend", "/api/v1/auth/login");

	public static final String HEADER = "X-CSRF-TOKEN";

	private final PreLoginCsrf tokens;

	private final CsrfProperties properties;

	private final ProblemResponseWriter problems;

	public PreLoginCsrfFilter(PreLoginCsrf tokens, CsrfProperties properties, ProblemResponseWriter problems) {
		this.tokens = tokens;
		this.properties = properties;
		this.problems = problems;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod()) || !PATHS.contains(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!originAllowed(request.getHeader("Origin")) || !tokens.isValid(cookie(request), request.getHeader(HEADER))) {
			problems.write(request, response, ErrorCode.CSRF_REJECTED);
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean originAllowed(String origin) {
		if (origin == null || properties.allowedOrigins().isEmpty()) {
			return true;
		}
		return properties.allowedOrigins().contains(origin);
	}

	private String cookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookie.getName().equals(properties.cookieName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
