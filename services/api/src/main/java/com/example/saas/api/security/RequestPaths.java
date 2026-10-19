package com.example.saas.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * The path of a request as routing and authorization see it: percent-decoded and without path parameters. The
 * filters that guard {@code /api/} must use this and never {@code getRequestURI()}, which keeps the raw form, so
 * {@code /%61pi/...} cannot slip past a prefix check and still reach a controller.
 */
final class RequestPaths {

	private RequestPaths() {
	}

	static String of(HttpServletRequest request) {
		return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
	}

}
