package com.example.saas.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Skeleton policy: deny by default. Authentication, sessions and CSRF handling arrive with SPEC-AUTH;
 * until then only the health endpoints are reachable and no authentication mechanism is enabled.
 */
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().denyAll())
			.csrf(Customizer.withDefaults());
		return http.build();
	}
}
