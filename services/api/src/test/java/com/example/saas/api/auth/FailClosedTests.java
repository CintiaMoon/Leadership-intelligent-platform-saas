package com.example.saas.api.auth;

import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-21: when the counter store is unavailable, the rate-limited endpoints refuse the request and do nothing. */
@Import(FailClosedTests.StoreDown.class)
class FailClosedTests extends AuthIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class StoreDown {

		@Bean
		@Primary
		RateLimiter unavailableRateLimiter() {
			return new RateLimiter() {

				@Override
				public RateLimitResult hit(RateLimitPolicy policy, String identifier) {
					throw new RateLimitStoreUnavailableException(new RuntimeException("connection refused"));
				}

				@Override
				public RateLimitResult current(RateLimitPolicy policy, String identifier) {
					throw new RateLimitStoreUnavailableException(new RuntimeException("connection refused"));
				}

			};
		}

	}

	@Test
	void signupIsRejectedAndCreatesNothing() throws Exception {
		String email = uniqueEmail();
		int outboxBefore = count("SELECT count(*) FROM outbox_event");

		MvcResult result = api.signup(email, STRONG_PASSWORD, "Fail Closed", freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(count("SELECT count(*) FROM app_user WHERE email_normalized = ?", email)).isZero();
		assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(outboxBefore);
	}

	@Test
	void resendIsRejectedAndQueuesNothing() throws Exception {
		int outboxBefore = count("SELECT count(*) FROM outbox_event");

		MvcResult result = api.resend(uniqueEmail(), freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(outboxBefore);
	}

	@Test
	void passwordResetRequestIsRejectedAndQueuesNothing() throws Exception {
		int outboxBefore = count("SELECT count(*) FROM outbox_event");

		MvcResult result = api.requestReset(uniqueEmail(), freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(outboxBefore);
	}

	@Test
	void loginIsRejectedAndOpensNoSession() throws Exception {
		int sessionsBefore = count("SELECT count(*) FROM spring_session");

		MvcResult result = api.login(uniqueEmail(), STRONG_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(AuthApi.sessionCookie(result)).isNull();
		assertThat(count("SELECT count(*) FROM spring_session")).isEqualTo(sessionsBefore);
	}

	@Test
	void verifyingATokenDoesNotDependOnTheCounterStore() throws Exception {
		MvcResult result = api.verify("x".repeat(43), freshIp());

		assertThat(result.getResponse().getStatus()).as("an invalid token is still a normal 422").isEqualTo(422);
	}

}
