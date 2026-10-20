package com.example.saas.api.auth;

import com.example.saas.api.auth.domain.PasswordHashProperties;
import com.example.saas.api.auth.email.AppProperties;
import com.example.saas.api.security.SessionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableConfigurationProperties({ AppProperties.class, PasswordHashProperties.class, SessionProperties.class,
		com.example.saas.api.retention.RetentionProperties.class })
class AuthConfig {

	/**
	 * The session cookie is defined here, not through {@code server.servlet.session.cookie}, because that setting
	 * is applied only when an embedded web server is running; this way it is identical in tests and in
	 * production (ADR-001): HttpOnly, SameSite=Lax, Path=/, no Domain, and a browser-session lifetime. The
	 * 12-hour limit is enforced on the server, not by the cookie.
	 */
	@Bean
	DefaultCookieSerializer cookieSerializer(SessionProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName(properties.cookieName());
		serializer.setUseSecureCookie(properties.cookieSecure());
		serializer.setUseHttpOnlyCookie(true);
		serializer.setSameSite("Lax");
		serializer.setCookiePath("/");
		return serializer;
	}

}
