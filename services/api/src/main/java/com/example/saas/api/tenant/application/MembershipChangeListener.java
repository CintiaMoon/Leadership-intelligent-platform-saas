package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;

/**
 * Told, inside the same transaction and under the company lock, that a member's role or status was just changed through
 * {@link MembershipService} (D-62, D-158). Other modules react to it without {@code MembershipService} having to know
 * them: the invitation module revokes the pending invitations the member can no longer grant. A listener must not send
 * anything or call the network, and must take no lock that comes before the company row.
 */
public interface MembershipChangeListener {

	/**
	 * @param companyId the company, whose row the caller has locked
	 * @param userId the member whose membership changed
	 * @param role the member's role after the change
	 * @param status the member's status after the change (ACTIVE, SUSPENDED or REMOVED)
	 * @param changedBy the user who made the change (the member themselves when they leave)
	 */
	void afterMembershipChange(UUID companyId, UUID userId, Role role, MembershipStatus status, UUID changedBy);

}
