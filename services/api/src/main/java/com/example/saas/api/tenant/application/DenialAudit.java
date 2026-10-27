package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.plan.domain.SubscriptionState;
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
public class DenialAudit {

	private final AuditLog audit;

	private final RateLimiter rateLimiter;

	DenialAudit(AuditLog audit, RateLimiter rateLimiter) {
		this.audit = audit;
		this.rateLimiter = rateLimiter;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void denied(UUID userId, UUID companyId, Permission permission) {
		if (firstInWindow(userId, companyId, permission)) {
			audit.recordForCompany("tenant.authorization.denied", AuditLog.Actor.user, userId, "company", companyId,
					companyId, "denied", permission.code());
		}
	}

	/**
	 * A request refused because of the subscription state (AC-PLAN-22). Same window as a permission denial, counted per
	 * permission and state; the reason carries the permission and the state is recorded as the plan field it is.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void restricted(UUID userId, UUID companyId, Permission permission, SubscriptionState state) {
		if (firstInWindow(userId, companyId, permission, state.name())) {
			audit.recordChange("tenant.authorization.denied", AuditLog.Actor.user, userId, "company", companyId,
					companyId, "denied", "plan_restricted:" + permission.code(), state.name(), null);
		}
	}

	private boolean firstInWindow(UUID userId, UUID companyId, Permission permission, String state) {
		try {
			return rateLimiter
				.hit(CompanyRateLimits.DENIAL_AUDIT, userId + ":" + companyId + ":" + permission.code() + ":" + state)
				.allowed();
		}
		catch (RateLimitStoreUnavailableException e) {
			return true;
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
