package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.plan.application.EntitlementService;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import org.springframework.stereotype.Component;

/**
 * Applies the subscription-state dimension of {@link CompanyPolicy} for the services that already decide the role
 * (plan Q-10). Call it after the role check and refuse early, before any lock; call it again once the company lock is
 * held, because the lock is where the state is stable against an operator plan change. The state is read fresh each time
 * from the database with the injected clock, never from the request.
 */
@Component
public class PlanGate {

	private final EntitlementService entitlements;

	PlanGate(EntitlementService entitlements) {
		this.entitlements = entitlements;
	}

	/** @throws PlanRestrictedException the state does not allow the permission to this role */
	public void require(UUID userId, UUID companyId, Permission permission, Role role) {
		SubscriptionState state = entitlements.state(companyId);
		if (!CompanyPolicy.stateAllows(permission, role, state)) {
			throw new PlanRestrictedException(userId, companyId, permission, state);
		}
	}

	/** Revoking an invitation is allowed in more states than resending it (plan section 3a). */
	public void requireRevokingInvitation(UUID userId, UUID companyId) {
		SubscriptionState state = entitlements.state(companyId);
		if (!CompanyPolicy.stateAllowsRevokingInvitation(state)) {
			throw new PlanRestrictedException(userId, companyId, Permission.REVOKE_OR_RESEND_INVITATIONS, state);
		}
	}

	/** The state now, for a transaction that decides on it together with something else. */
	public SubscriptionState state(UUID companyId) {
		return entitlements.state(companyId);
	}

}
