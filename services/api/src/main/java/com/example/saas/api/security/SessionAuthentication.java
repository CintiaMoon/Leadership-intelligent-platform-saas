package com.example.saas.api.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The authentication for a request that carries a valid session. Tenant roles are added by later slices. */
public final class SessionAuthentication extends AbstractAuthenticationToken {

	private final AuthenticatedUser user;

	public SessionAuthentication(AuthenticatedUser user) {
		super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
		this.user = user;
		setAuthenticated(true);
	}

	public AuthenticatedUser user() {
		return user;
	}

	@Override
	public Object getPrincipal() {
		return user;
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return user.userId().toString();
	}

}
