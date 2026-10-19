package com.example.saas.api.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.auth.web.SessionManager;
import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints and a security chain that mirrors the production filter setup for them (BFF trust, session
 * authentication, session-bound CSRF), so the foundation and the reusable mechanisms (recent authentication,
 * verified-only access, session rotation) are exercised over HTTP without adding any endpoint to the production chain.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
public class TestEndpointsConfiguration {

	@Bean
	@Order(1)
	SecurityFilterChain testEndpointsChain(HttpSecurity http, SecurityProperties properties,
			SessionProperties sessionProperties, ProblemResponseWriter problems, ProblemSecurityHandlers handlers,
			UserAccountRepository users, Clock clock, RecentAuthenticationAuthorizationManager recentAuthentication)
			throws Exception {
		http
			.securityMatcher("/api/v1/test/**", "/api/v1/auth/test/**")
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
			.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.addFilterBefore(new BffTrustFilter(properties, problems), CsrfFilter.class)
			.addFilterBefore(new SessionAuthenticationFilter(users, sessionProperties, clock), CsrfFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/api/v1/test/sensitive").access(recentAuthentication)
				.requestMatchers("/api/v1/test/authenticated", "/api/v1/test/rotate").authenticated()
				.anyRequest().permitAll())
			.exceptionHandling(handling -> handling.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
			.csrf(Customizer.withDefaults());
		return http.build();
	}

	record Payload(@NotBlank String name, @Size(max = 5) String code) {
	}

	// Picked up by component scanning; its paths are denied by the production chain and only the chain above opens them.
	@RestController
	static class TestEndpointsController {

		static final RateLimitPolicy LIMITED = new RateLimitPolicy("test-endpoint-ip", 3, Duration.ofMinutes(10));

		private final RateLimiter rateLimiter;

		private final SessionManager sessions;

		TestEndpointsController(RateLimiter rateLimiter, SessionManager sessions) {
			this.rateLimiter = rateLimiter;
			this.sessions = sessions;
		}

		@GetMapping("/api/v1/test/ip")
		Map<String, String> ip(HttpServletRequest request) {
			ClientAddress address = ClientAddress.of(request);
			return Map.of("address", address.address(), "limitKey", address.limitKey());
		}

		@PostMapping("/api/v1/test/echo")
		Map<String, String> echo(@Valid @RequestBody Payload payload) {
			return Map.of("name", payload.name());
		}

		@GetMapping("/api/v1/test/boom")
		void boom() {
			throw new IllegalStateException("internal-detail-that-must-not-leak");
		}

		@GetMapping("/api/v1/test/store-down")
		void storeDown() {
			throw new RateLimitStoreUnavailableException(new RuntimeException("jdbc:postgresql://secret-host/db"));
		}

		@GetMapping("/api/v1/test/hash-busy")
		void hashBusy() {
			throw new com.example.saas.api.auth.domain.PasswordHashingBusyException();
		}

		@GetMapping("/api/v1/test/limited")
		ResponseEntity<Map<String, Object>> limited(HttpServletRequest request) {
			RateLimitResult result = rateLimiter.hit(LIMITED, ClientAddress.of(request).limitKey());
			return ResponseEntity.status(result.allowed() ? 200 : 429).body(Map.of("count", result.count()));
		}

		@GetMapping("/api/v1/auth/test/ping")
		Map<String, String> authPing() {
			return Map.of("ok", "true");
		}

		@GetMapping("/api/v1/test/ping")
		Map<String, String> ping() {
			return Map.of("ok", "true");
		}

		/** Any valid, verified session: the verified-only mechanism (AC-AUTH-04). */
		@GetMapping("/api/v1/test/authenticated")
		Map<String, String> authenticated() {
			return Map.of("ok", "true");
		}

		/** Needs a login or step-up within 10 minutes: the mechanism behind AC-AUTH-14 and AC-AUTH-22. */
		@GetMapping("/api/v1/test/sensitive")
		Map<String, String> sensitive() {
			return Map.of("ok", "true");
		}

		/** Stands in for a change to the caller's own role or membership (AC-AUTH-23). */
		@PostMapping("/api/v1/test/rotate")
		Map<String, String> rotate(HttpServletRequest request) {
			sessions.rotate(request);
			return Map.of("ok", "true");
		}

	}

}
