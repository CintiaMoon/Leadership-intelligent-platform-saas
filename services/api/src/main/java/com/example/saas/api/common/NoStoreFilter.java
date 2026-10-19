package com.example.saas.api.common;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication responses must not be cached (AC-AUTH-24). The header is set before the response is
 * written so that Spring Security's default cache headers, which only apply when no Cache-Control
 * header exists, do not replace the exact value the contract specifies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NoStoreFilter extends OncePerRequestFilter {

	static final String AUTH_PREFIX = "/api/v1/auth/";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(AUTH_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		chain.doFilter(request, response);
	}

}
