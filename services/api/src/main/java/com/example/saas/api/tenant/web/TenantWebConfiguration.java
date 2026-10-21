package com.example.saas.api.tenant.web;

import java.util.List;

import com.example.saas.api.tenant.application.CompanyAuthorizationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class TenantWebConfiguration implements WebMvcConfigurer {

	private final CompanyAuthorizationService authorization;

	TenantWebConfiguration(CompanyAuthorizationService authorization) {
		this.authorization = authorization;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new AuthorizedCompanyArgumentResolver(authorization));
	}

}
