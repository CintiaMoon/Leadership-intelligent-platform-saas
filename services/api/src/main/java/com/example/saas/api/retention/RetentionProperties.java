package com.example.saas.api.retention;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long operational records are kept (D-100). The periods are provisional until legal review (D-87).
 *
 * @param outboxSent delivered outbox events (their payload is already scrubbed when sent)
 * @param outboxFailed outbox events that used up their retries; kept longer for repair
 * @param failedLoginAudit audit events of failed logins
 * @param interval delay between purge runs
 */
@ConfigurationProperties("app.retention")
public record RetentionProperties(
		@DefaultValue("7d") Duration outboxSent,
		@DefaultValue("30d") Duration outboxFailed,
		@DefaultValue("365d") Duration failedLoginAudit,
		@DefaultValue("6h") Duration interval) {

	public RetentionProperties {
		if (outboxSent.isNegative() || outboxFailed.isNegative() || failedLoginAudit.isNegative()
				|| interval.compareTo(Duration.ofMinutes(1)) < 0) {
			throw new IllegalStateException("Invalid app.retention settings");
		}
	}

}
