package com.example.saas.api.config;

import java.time.Clock;

import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.security.BffTrustFilter;
import com.example.saas.api.security.CsrfProperties;
import com.example.saas.api.security.DeviceProperties;
import com.example.saas.api.security.PreLoginCsrf;
import com.example.saas.api.security.PreLoginCsrfFilter;
import com.example.saas.api.security.ProblemSecurityHandlers;
import com.example.saas.api.security.SecurityProperties;
import com.example.saas.api.security.SessionAuthenticationFilter;
import com.example.saas.api.security.SessionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Deny by default (ADR-001). Every /api request must present the BFF credential before anything else is
 * evaluated. Identity comes only from a valid server-side session (Spring Session JDBC), turned into an
 * authentication by {@link SessionAuthenticationFilter}; Spring Security never stores its own security context
 * in the session. Two CSRF checks apply (D-75): the signed double-submit token for requests made before a
 * session exists, and Spring Security's session-bound token for everything else.
 */
@Configuration
@EnableConfigurationProperties({ SecurityProperties.class, CsrfProperties.class, DeviceProperties.class })
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
			CsrfProperties csrfProperties, SessionProperties sessionProperties, PreLoginCsrf preLoginCsrf,
			ProblemResponseWriter problems, ProblemSecurityHandlers handlers, UserAccountRepository users,
			Clock clock) throws Exception {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		RequestMatcher[] preLoginPosts = PreLoginCsrfFilter.PATHS.stream()
			.map(path -> paths.matcher(HttpMethod.POST, path))
			.toArray(RequestMatcher[]::new);
		http
			// Sessions are created explicitly at login; Spring Security must not create or persist any of its own.
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
			.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.addFilterBefore(new BffTrustFilter(properties, problems), CsrfFilter.class)
			.addFilterBefore(new PreLoginCsrfFilter(preLoginCsrf, csrfProperties, problems), CsrfFilter.class)
			.addFilterBefore(new SessionAuthenticationFilter(users, sessionProperties, clock), CsrfFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(preLoginPosts).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/session").authenticated()
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout", "/api/v1/auth/step-up").authenticated()
				// A valid session already means a verified, ACTIVE account (SessionAuthenticationFilter), which is what
				// company creation needs (AC-TEN-07, 11). Membership and role are checked per company by the service.
				.requestMatchers(HttpMethod.POST, "/api/v1/companies").authenticated()
				.requestMatchers(HttpMethod.GET, "/api/v1/companies", "/api/v1/companies/*").authenticated()
				// Membership routes (SPEC-MEM). Who may do what is decided per company by the service.
				.requestMatchers(HttpMethod.GET, "/api/v1/companies/*/members").authenticated()
				.requestMatchers(HttpMethod.PATCH, "/api/v1/companies/*/members/*").authenticated()
				.requestMatchers(HttpMethod.DELETE, "/api/v1/companies/*/members/*").authenticated()
				.requestMatchers(HttpMethod.POST, "/api/v1/companies/*/members/*/suspend",
						"/api/v1/companies/*/members/*/reactivate", "/api/v1/companies/*/leave")
					.authenticated()
				// Invitation management (SPEC-INV). Who may do what is decided per company by the service.
				.requestMatchers(HttpMethod.GET, "/api/v1/companies/*/invitations").authenticated()
				.requestMatchers(HttpMethod.POST, "/api/v1/companies/*/invitations",
						"/api/v1/companies/*/invitations/*/resend")
					.authenticated()
				.requestMatchers(HttpMethod.DELETE, "/api/v1/companies/*/invitations/*").authenticated()
				.anyRequest().denyAll())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(handlers)
				.accessDeniedHandler(handlers))
			// Requests made before a session exists have their own signed double-submit check; everything else
			// keeps Spring Security's session-bound CSRF protection.
			.csrf(csrf -> csrf.ignoringRequestMatchers(preLoginPosts));
		return http.build();
	}
}
