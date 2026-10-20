package com.example.saas.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The trust and CSRF filters must see the same path that authorization sees. A servlet container decodes
 * {@code %61pi} to {@code api} for routing while {@code getRequestURI()} keeps the raw form, so the request is
 * built the way the container presents it: raw URI encoded, servlet path decoded.
 */
class EncodedPathTests extends AuthIntegrationTest {

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder encoded(String raw, String decoded) {
		return post(decoded).with(request -> {
			request.setRequestURI(raw);
			request.setServletPath(decoded);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}");
	}

	@Test
	void anEncodedApiPathStillNeedsTheBffCredential() throws Exception {
		int status = mvc.perform(encoded("/%61pi/v1/auth/verify-email", "/api/v1/auth/verify-email"))
			.andReturn()
			.getResponse()
			.getStatus();

		assertThat(status).isEqualTo(401);
	}

	@Test
	void anEncodedPreLoginPathStillNeedsTheCsrfToken() throws Exception {
		int status = mvc.perform(encoded("/api/v1/auth/%6Cogin", "/api/v1/auth/login")
			.header("X-BFF-Credential", AuthApi.BFF_CREDENTIAL)
			.header("X-Forwarded-For", AuthApi.freshIp()))
			.andReturn()
			.getResponse()
			.getStatus();

		assertThat(status).isEqualTo(403);
	}

}
