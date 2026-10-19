package com.example.saas.api.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 9457 problem responses from places that sit outside Spring MVC (servlet and security
 * filters). The body never contains request data, exception messages, or stack traces.
 */
@Component
public class ProblemResponseWriter {

	public static final String CONTENT_TYPE = "application/problem+json";

	private final JsonMapper jsonMapper;

	public ProblemResponseWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
		write(request, response, code, List.of(), null);
	}

	public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code,
			List<FieldProblem> errors, Integer retryAfterSeconds) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", code.type());
		body.put("title", code.status().getReasonPhrase());
		body.put("status", code.status().value());
		body.put("detail", code.detail());
		// Path only: the query string can carry data that must not be echoed.
		body.put("instance", request.getRequestURI());
		body.put("code", code.name());
		if (!errors.isEmpty()) {
			body.put("errors", errors);
		}
		response.setStatus(code.status().value());
		response.setContentType(CONTENT_TYPE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		if (retryAfterSeconds != null) {
			response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
		}
		response.getWriter().write(jsonMapper.writeValueAsString(body));
	}

}
