package com.example.saas.api.auth.web;

import java.time.Duration;

import com.example.saas.api.security.CsrfProperties;
import com.example.saas.api.security.PreLoginCsrf;
import com.example.saas.api.security.PreLoginCsrfFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issues the pre-login CSRF token as a cookie and in the body (D-75). Session-bound tokens follow in Phase 3. */
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
	CsrfTokenResponse csrf(HttpServletResponse response) {
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
