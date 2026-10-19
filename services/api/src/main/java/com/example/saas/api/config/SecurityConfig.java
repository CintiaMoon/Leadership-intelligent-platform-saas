package com.example.saas.api.config;

import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.security.BffTrustFilter;
import com.example.saas.api.security.CsrfProperties;
import com.example.saas.api.security.PreLoginCsrf;
import com.example.saas.api.security.PreLoginCsrfFilter;
import com.example.saas.api.security.ProblemSecurityHandlers;
import com.example.saas.api.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Deny by default. Every /api request must present the BFF credential before anything else is evaluated
 * (ADR-001). The only endpoints opened so far are the ones used before a session exists; they are
 * protected by the pre-login CSRF token (D-75). Sessions and login arrive in the next SPEC-AUTH phase, so
 * the chain is stateless until then.
 */
@Configuration
@EnableConfigurationProperties({ SecurityProperties.class, CsrfProperties.class })
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
			CsrfProperties csrfProperties, PreLoginCsrf preLoginCsrf, ProblemResponseWriter problems,
			ProblemSecurityHandlers handlers) throws Exception {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		RequestMatcher[] preLoginPosts = PreLoginCsrfFilter.PATHS.stream()
			.map(path -> paths.matcher(HttpMethod.POST, path))
			.toArray(RequestMatcher[]::new);
		http
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(new BffTrustFilter(properties, problems), CsrfFilter.class)
			.addFilterBefore(new PreLoginCsrfFilter(preLoginCsrf, csrfProperties, problems), CsrfFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(preLoginPosts).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
				.anyRequest().denyAll())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(handlers)
				.accessDeniedHandler(handlers))
			// The pre-login endpoints have their own signed double-submit check; everything else keeps
			// Spring Security's CSRF protection.
			.csrf(csrf -> csrf.ignoringRequestMatchers(preLoginPosts));
		return http.build();
	}
}
