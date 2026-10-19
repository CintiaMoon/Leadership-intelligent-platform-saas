package com.example.saas.api.auth.email;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application-wide settings used when building links and mail.
 *
 * @param publicBaseUrl the public address of the web app, used for links in emails. Always configured; it is
 *        never derived from the {@code Host} header, which a caller controls (reset-link poisoning)
 * @param mail sender settings
 */
@ConfigurationProperties("app")
public record AppProperties(String publicBaseUrl, @DefaultValue Mail mail) {

	public record Mail(@DefaultValue("no-reply@liptech.io") String from) {
	}

	public AppProperties {
		if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
			throw new IllegalStateException("app.public-base-url (PUBLIC_BASE_URL) must be set, for example https://app.liptech.io");
		}
		URI uri = URI.create(publicBaseUrl);
		if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
				|| uri.getRawQuery() != null || uri.getRawFragment() != null) {
			throw new IllegalStateException("app.public-base-url must be an http(s) URL without query or fragment");
		}
		publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
				: publicBaseUrl;
	}

}
