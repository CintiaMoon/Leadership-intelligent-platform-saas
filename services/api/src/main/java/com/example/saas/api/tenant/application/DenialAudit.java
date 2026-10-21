package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.tenant.domain.Permission;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a denial in its own transaction, so it survives the failed request (D-115). At most one row per user, company
 * and permission per window (D-121). If the counter store is unavailable the row is written anyway: losing audit
 * evidence is worse than a few extra rows, and the response stays 403, not 503.
 */
@Component
class DenialAudit {

	private final AuditLog audit;

	private final RateLimiter rateLimiter;

	DenialAudit(AuditLog audit, RateLimiter rateLimiter) {
		this.audit = audit;
		this.rateLimiter = rateLimiter;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void denied(UUID userId, UUID companyId, Permission permission) {
		if (firstInWindow(userId, companyId, permission)) {
			audit.recordForCompany("tenant.authorization.denied", AuditLog.Actor.user, userId, "company", companyId,
					companyId, "denied", permission.code());
		}
	}

	/** True for the first denial of this kind in the window, or when the store cannot say. */
	private boolean firstInWindow(UUID userId, UUID companyId, Permission permission) {
		try {
			return rateLimiter.hit(CompanyRateLimits.DENIAL_AUDIT, userId + ":" + companyId + ":" + permission.code())
				.allowed();
		}
		catch (RateLimitStoreUnavailableException e) {
			return true;
		}
	}

}
