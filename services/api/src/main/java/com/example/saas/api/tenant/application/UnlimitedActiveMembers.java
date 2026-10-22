package com.example.saas.api.tenant.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The default until plans exist (D-134): no limit. Slice 006 replaces this class with the plan-based implementation
 * (deleting it, or defining its own {@code @Primary} bean); until then nothing limits reactivation.
 */
@Component
class UnlimitedActiveMembers implements ActiveMemberLimit {

	@Override
	public boolean hasRoomForAnotherActiveMember(UUID companyId) {
		return true;
	}

}
