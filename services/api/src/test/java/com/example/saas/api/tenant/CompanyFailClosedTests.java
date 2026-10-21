package com.example.saas.api.tenant;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** Company creation fails closed when the counter store is unavailable (D-107, ADR-004). */
@Import(CompanyFailClosedTests.SwitchableStore.class)
class CompanyFailClosedTests extends AuthIntegrationTest {

	/** Allows everything until switched off, so the test can sign in first and then take the store down. */
	static class Switch implements RateLimiter {

		final AtomicBoolean down = new AtomicBoolean();

		@Override
		public RateLimitResult hit(RateLimitPolicy policy, String identifier) {
			if (down.get()) {
				throw new RateLimitStoreUnavailableException(new RuntimeException("connection refused"));
			}
			return new RateLimitResult(true, 1, policy.limit(), Instant.now().plus(policy.window()));
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SwitchableStore {

		@Bean
		@Primary
		Switch switchableRateLimiter() {
			return new Switch();
		}

	}

	@Autowired
	private Switch store;

	@AfterEach
	void restoreStore() {
		store.down.set(false);
	}

	@Test
	void creationIsRejectedWith503AndCreatesNothing() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		String ip = freshIp();
		String csrf = api.sessionCsrf(cookie, ip);
		int before = count("SELECT count(*) FROM company");
		store.down.set(true);

		MvcResult result = api.postWithSession("/api/v1/companies", json("name", "Never"), cookie, csrf, ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(count("SELECT count(*) FROM company")).isEqualTo(before);
		assertThat(count("SELECT count(*) FROM membership WHERE user_id = ?", userId)).isZero();
	}

}
