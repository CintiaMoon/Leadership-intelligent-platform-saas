package com.example.saas.api.auth.web;

import java.time.Duration;

import com.example.saas.api.security.CsrfProperties;
import com.example.saas.api.security.PreLoginCsrf;
import com.example.saas.api.security.PreLoginCsrfFilter;
import com.example.saas.api.security.SessionAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues the CSRF token for the caller (D-75). With a valid session it is the token bound to that session; without
 * one it is the stateless signed double-submit token, which is also set as a cookie.
 */
@RestController
@RequestMapping("/api/v1/auth")
class CsrfController {

	private final PreLoginCsrf tokens;

	private final CsrfProperties properties;

	CsrfController(PreLoginCsrf tokens, CsrfProperties properties) {
		this.tokens = tokens;
		this.properties = properties;
	}

	@GetMapping("/csrf")
	CsrfTokenResponse csrf(HttpServletRequest request, HttpServletResponse response) {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof SessionAuthentication
				&& request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken sessionToken) {
			return new CsrfTokenResponse(sessionToken.getHeaderName(), sessionToken.getToken());
		}
		String token = tokens.issue();
		ResponseCookie cookie = ResponseCookie.from(properties.cookieName(), token)
			.httpOnly(true)
			.secure(properties.cookieSecure())
			.sameSite("Lax")
			.path("/")
			.maxAge(Duration.ofSeconds(properties.maxAge().toSeconds()))
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return new CsrfTokenResponse(PreLoginCsrfFilter.HEADER, token);
	}

}
