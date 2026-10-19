package com.example.saas.api.security;

import java.time.Duration;
import java.util.Map;

import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints and a security chain that mirrors the production filter setup for them, so the
 * foundation (BFF trust, problem format, headers, rate limiting) is exercised over HTTP without adding any
 * public endpoint to the production chain.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEndpointsConfiguration {

	@Bean
	@Order(1)
	SecurityFilterChain testEndpointsChain(HttpSecurity http, SecurityProperties properties,
			ProblemResponseWriter problems, ProblemSecurityHandlers handlers) throws Exception {
		http
			.securityMatcher("/api/v1/test/**", "/api/v1/auth/test/**")
			.addFilterBefore(new BffTrustFilter(properties, problems), CsrfFilter.class)
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
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

		TestEndpointsController(RateLimiter rateLimiter) {
			this.rateLimiter = rateLimiter;
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

	}

}
