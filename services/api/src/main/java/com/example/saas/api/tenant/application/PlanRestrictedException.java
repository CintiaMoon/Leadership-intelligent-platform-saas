package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.domain.Permission;

/**
 * An ACTIVE member whose role holds the permission, refused because the company's subscription state does not allow it
 * (AC-PLAN-22). The caller is a member, so the response may name the state. Like {@link PermissionDeniedException} it
 * carries what the handler audits once the transaction has ended (D-121).
 */
public class PlanRestrictedException extends RuntimeException {

	private final transient UUID userId;

	private final transient UUID companyId;

	private final transient Permission permission;

	private final SubscriptionState state;

	public PlanRestrictedException(UUID userId, UUID companyId, Permission permission, SubscriptionState state) {
		super(null, null, false, false);
		this.userId = userId;
		this.companyId = companyId;
		this.permission = permission;
		this.state = state;
	}

	public UUID userId() {
		return userId;
	}

	public UUID companyId() {
		return companyId;
	}

	public Permission permission() {
		return permission;
	}

	public SubscriptionState state() {
		return state;
	}

}
