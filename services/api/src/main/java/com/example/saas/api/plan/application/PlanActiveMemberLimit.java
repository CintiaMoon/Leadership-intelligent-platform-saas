package com.example.saas.api.plan.application;

import java.util.UUID;

import com.example.saas.api.plan.domain.LimitKey;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.application.ActiveMemberLimit;
import org.springframework.stereotype.Component;

/**
 * The plan-based member limit (Q-11, D-134): replaces the unlimited default of slice 003. Called by reactivation and by
 * invitation acceptance, the only operations that add an ACTIVE member, after the company row is locked, so N
 * simultaneous calls at limit minus one admit exactly one (AC-PLAN-05). Suspended members and directory people are not
 * counted. Inviting is never capped (D-159).
 *
 * <p>
 * A company in NO_PLAN is not limited here: it has no limit to compare with, and the gating of Q-08 refuses the
 * operation before this is asked. Expired states keep the Trial's limit, so they are counted like a trial.
 */
@Component
class PlanActiveMemberLimit implements ActiveMemberLimit {

	private final EntitlementService entitlements;

	PlanActiveMemberLimit(EntitlementService entitlements) {
		this.entitlements = entitlements;
	}

	@Override
	public boolean hasRoomForAnotherActiveMember(UUID companyId) {
		if (entitlements.state(companyId) == SubscriptionState.NO_PLAN) {
			return true;
		}
		return entitlements.hasRoomFor(companyId, LimitKey.ACTIVE_MEMBERS);
	}

}
