package com.example.saas.api.common;

import java.util.Set;
import java.util.UUID;

import com.example.saas.api.TestcontainersConfiguration;
import com.example.saas.api.security.TestEndpointsConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BFF trust, problem format, correlation, no-store, and rate limiting over HTTP (SPEC-AUTH Phase 1). */
@Import({ TestcontainersConfiguration.class, TestEndpointsConfiguration.class })
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class FoundationHttpTests {

	static final String BFF = "X-BFF-Credential";

	static final String CREDENTIAL = "test-bff-credential-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	@Autowired
	MockMvc mockMvc;

	private static RequestPostProcessor remote(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	@Test
	void apiRequestWithoutBffCredentialIsRefusedAsAProblem() throws Exception {
		mockMvc.perform(get("/api/v1/test/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.instance").value("/api/v1/test/ping"));
	}

	@Test
	void unknownApiPathWithCredentialIsDeniedByDefaultInProblemFormat() throws Exception {
		mockMvc.perform(get("/api/v1/anything").header(BFF, CREDENTIAL))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void healthIsPublicWithoutTheCredential() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void clientAddressComesFromTheTrustedForwardedHeaderOnly() throws Exception {
		mockMvc.perform(get("/api/v1/test/ip").header(BFF, CREDENTIAL).header("X-Forwarded-For", "6.6.6.6, 203.0.113.9"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.address").value("203.0.113.9"));
		mockMvc.perform(get("/api/v1/test/ip")
			.header(BFF, CREDENTIAL)
			.header("X-Forwarded-For", "6.6.6.6")
			.with(remote("198.51.100.7")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.address").value("198.51.100.7"));
	}

	@Test
	void validationFailureIsAProblemWithFieldErrorsAndNoEchoedValues() throws Exception {
		mockMvc.perform(post("/api/v1/test/echo")
			.header(BFF, CREDENTIAL)
			.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"\",\"code\":\"toolongvalue-SECRET\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field=='name')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.errors[?(@.field=='code')].code").value("INVALID_LENGTH"))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
	}

	@Test
	void malformedJsonDoesNotEchoTheBody() throws Exception {
		mockMvc.perform(post("/api/v1/test/echo")
			.header(BFF, CREDENTIAL)
			.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\": hunter2-body-secret"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
	}

	@Test
	void unexpectedErrorIsAGenericProblemWithoutInternalDetail() throws Exception {
		mockMvc.perform(get("/api/v1/test/boom").header(BFF, CREDENTIAL))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-not-leak"))))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
	}

	@Test
	void wrongMethodIsARejectedRequestProblem() throws Exception {
		mockMvc.perform(get("/api/v1/test/echo").header(BFF, CREDENTIAL))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
	}

	@Test
	void stateChangingRequestWithoutCsrfTokenIsRejectedAsCsrfProblem() throws Exception {
		mockMvc.perform(post("/api/v1/test/echo")
			.header(BFF, CREDENTIAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
	}

	@Test
	void unavailableCounterStoreFailsClosedWithRetryAfterAndNoConnectionDetail() throws Exception {
		mockMvc.perform(get("/api/v1/test/store-down").header(BFF, CREDENTIAL))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-host"))));
	}

	@Test
	void exhaustedHashingCapacityIsA503WithRetryAfter() throws Exception {
		mockMvc.perform(get("/api/v1/test/hash-busy").header(BFF, CREDENTIAL))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}

	@Test
	void authenticationResponsesCarryExactlyNoStore() throws Exception {
		mockMvc.perform(get("/api/v1/auth/test/ping").header(BFF, CREDENTIAL))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"));
		// Refusals on auth paths are not cacheable either.
		mockMvc.perform(get("/api/v1/auth/test/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void everyRequestGetsItsOwnServerGeneratedCorrelationId() throws Exception {
		String clientValue = "client-chosen-" + UUID.randomUUID();
		Set<String> ids = new java.util.HashSet<>();
		for (int i = 0; i < 3; i++) {
			String id = mockMvc.perform(get("/api/v1/test/ping").header(BFF, CREDENTIAL).header("X-Request-Id", clientValue))
				.andReturn()
				.getResponse()
				.getHeader("X-Request-Id");
			ids.add(id);
		}
		assertThat(ids).hasSize(3).doesNotContain(clientValue);
		ids.forEach(id -> assertThat(UUID.fromString(id)).isNotNull());
	}

	@Test
	void ipRateLimitAllowsThreeThenRefusesPerClientAddress() throws Exception {
		String forwarded = "203.0.113." + (1 + (int) (Math.random() * 200));
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/api/v1/test/limited").header(BFF, CREDENTIAL).header("X-Forwarded-For", forwarded))
				.andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/test/limited").header(BFF, CREDENTIAL).header("X-Forwarded-For", forwarded))
			.andExpect(status().isTooManyRequests());
		// A spoofed leading entry does not give the client a fresh bucket.
		mockMvc.perform(get("/api/v1/test/limited")
			.header(BFF, CREDENTIAL)
			.header("X-Forwarded-For", "9.9.9.9, " + forwarded))
			.andExpect(status().isTooManyRequests());
	}

}
