package com.example.saas.api.tenant.web;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.application.CompanyAuthorizationService;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives controllers an {@link AuthorizedCompany} so none of them reads the path variable or checks a role itself.
 * A malformed or unknown company ID and a missing membership all end as "not found".
 */
class AuthorizedCompanyArgumentResolver implements HandlerMethodArgumentResolver {

	static final String PATH_VARIABLE = "companyId";

	/** The canonical 36-character form only; UUID.fromString also accepts shortened forms such as 1-1-1-1-1. */
	private static final Pattern CANONICAL_UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final CompanyAuthorizationService authorization;

	AuthorizedCompanyArgumentResolver(CompanyAuthorizationService authorization) {
		this.authorization = authorization;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == AuthorizedCompany.class
				&& parameter.hasParameterAnnotation(Authorized.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest request, WebDataBinderFactory binderFactory) {
		Authorized authorized = parameter.getParameterAnnotation(Authorized.class);
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
			// Spring Security rejects anonymous callers before a controller runs; this only guards a misconfiguration.
			throw new IllegalStateException("A tenant endpoint was reached without an authenticated user");
		}
		@SuppressWarnings("unchecked")
		Map<String, String> variables = (Map<String, String>) request
			.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
		return authorization.require(user.userId(), parseCompanyId(variables), authorized.value());
	}

	private static UUID parseCompanyId(Map<String, String> variables) {
		String raw = variables == null ? null : variables.get(PATH_VARIABLE);
		if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
			throw new CompanyNotFoundException();
		}
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException e) {
			throw new CompanyNotFoundException();
		}
	}

}
