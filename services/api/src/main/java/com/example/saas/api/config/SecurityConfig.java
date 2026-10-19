package com.example.saas.api.config;

import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.security.BffTrustFilter;
import com.example.saas.api.security.ProblemSecurityHandlers;
import com.example.saas.api.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * Deny by default. Authentication, sessions and the CSRF design (D-75) arrive in later SPEC-AUTH phases;
 * until then only the health endpoints are reachable without the BFF credential, and every /api request
 * must present it before anything else is evaluated.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
			ProblemResponseWriter problems, ProblemSecurityHandlers handlers) throws Exception {
		http
			.addFilterBefore(new BffTrustFilter(properties, problems), CsrfFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().denyAll())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(handlers)
				.accessDeniedHandler(handlers))
			.csrf(Customizer.withDefaults());
		return http.build();
	}
}
