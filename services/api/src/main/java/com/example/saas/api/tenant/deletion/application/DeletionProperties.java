package com.example.saas.api.tenant.deletion.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Company deletion settings (SPEC-DEL, plan Q-04, Q-26).
 *
 * @param gracePeriod time between a deletion request and the earliest purge (D-37: 30 days). The deadline is stored with
 *        the request, so changing this does not move a deadline that already exists
 * @param emailCooldown a company's deletion emails of one kind (requested, cancelled) are sent at most once in this
 *        period, so a request-and-cancel loop cannot flood the Owner and the Admins (plan Q-26)
 */
@ConfigurationProperties("app.deletion")
public record DeletionProperties(
		@DefaultValue("30d") Duration gracePeriod,
		@DefaultValue("1h") Duration emailCooldown) {

	public DeletionProperties {
		if (gracePeriod.compareTo(Duration.ofSeconds(1)) < 0 || emailCooldown.isNegative()) {
			throw new IllegalStateException("Invalid app.deletion settings");
		}
	}

}
