package com.example.saas.api.tenant;

import java.time.Clock;

import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.security.BffTrustFilter;
import com.example.saas.api.security.ProblemSecurityHandlers;
import com.example.saas.api.security.SecurityProperties;
import com.example.saas.api.security.SessionAuthenticationFilter;
import com.example.saas.api.security.SessionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * The security chain that mirrors the production filters for the test-only tenant routes in
 * {@link PermissionRoutesController} (D-116).
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
public class TenantTestEndpointsConfiguration {

	@Bean
	@Order(2)
	SecurityFilterChain tenantTestEndpointsChain(HttpSecurity http, SecurityProperties properties,
			SessionProperties sessionProperties, ProblemResponseWriter problems, ProblemSecurityHandlers handlers,
			UserAccountRepository users, Clock clock) throws Exception {
		http
			.securityMatcher("/api/v1/companies/*/test/**")
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
			.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.addFilterBefore(new BffTrustFilter(properties, problems), CsrfFilter.class)
			.addFilterBefore(new SessionAuthenticationFilter(users, sessionProperties, clock), CsrfFilter.class)
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.exceptionHandling(handling -> handling.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
			.csrf(Customizer.withDefaults());
		return http.build();
	}

}
