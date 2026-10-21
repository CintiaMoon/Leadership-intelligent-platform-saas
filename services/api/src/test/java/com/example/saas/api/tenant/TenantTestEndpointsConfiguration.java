package com.example.saas.api.tenant;

import java.time.Clock;
import java.util.Map;

import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.common.ProblemResponseWriter;
import com.example.saas.api.security.BffTrustFilter;
import com.example.saas.api.security.ProblemSecurityHandlers;
import com.example.saas.api.security.SecurityProperties;
import com.example.saas.api.security.SessionAuthenticationFilter;
import com.example.saas.api.security.SessionProperties;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.web.Authorized;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only tenant routes (D-113) and a security chain that mirrors the production filters for them. Real endpoints
 * replace these as slices 003 to 007 land. Each route names its permission only through {@link Authorized}.
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

	@RestController
	static class TenantTestController {

		@GetMapping("/api/v1/companies/{companyId}/test/view")
		Map<String, String> view(@PathVariable String companyId,
				@Authorized(Permission.VIEW_COMPANY_DETAILS) AuthorizedCompany company) {
			return Map.of("role", company.role().name());
		}

		@GetMapping("/api/v1/companies/{companyId}/test/edit")
		Map<String, String> edit(@PathVariable String companyId,
				@Authorized(Permission.EDIT_CORE_RECORDS) AuthorizedCompany company) {
			return Map.of("role", company.role().name());
		}

		@GetMapping("/api/v1/companies/{companyId}/test/settings")
		Map<String, String> settings(@PathVariable String companyId,
				@Authorized(Permission.MANAGE_SETTINGS) AuthorizedCompany company) {
			return Map.of("role", company.role().name());
		}

	}

}
