package com.example.saas.api.security;

import java.io.IOException;

import com.example.saas.api.common.ErrorCode;
import com.example.saas.api.common.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/** Spring Security failures in the standard problem format (ADR-005). */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ProblemResponseWriter problems;

	ProblemSecurityHandlers(ProblemResponseWriter problems) {
		this.problems = problems;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		problems.write(request, response, ErrorCode.UNAUTHENTICATED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		ErrorCode code = ErrorCode.FORBIDDEN;
		if (accessDeniedException instanceof CsrfException) {
			code = ErrorCode.CSRF_REJECTED;
		}
		else if (accessDeniedException instanceof AuthorizationDeniedException denied
				&& denied.getAuthorizationResult() instanceof RecentAuthenticationAuthorizationManager.Stale) {
			code = ErrorCode.REAUTHENTICATION_REQUIRED;
		}
		problems.write(request, response, code);
	}

}
