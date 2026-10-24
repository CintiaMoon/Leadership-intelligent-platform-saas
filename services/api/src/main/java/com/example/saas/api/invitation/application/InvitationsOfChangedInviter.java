package com.example.saas.api.invitation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.invitation.domain.InvitationRules;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.tenant.application.MembershipChangeListener;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import org.springframework.stereotype.Component;

/**
 * D-62 and AC-INV-24: when an inviter is suspended, removed or leaves, or their role changes so that they can no longer
 * grant an invited role, the pending invitations they created for roles they can no longer grant are revoked, in the same
 * transaction as the membership change. Acceptance also re-checks the inviter, which covers changes made outside the
 * membership service (D-158).
 */
@Component
class InvitationsOfChangedInviter implements MembershipChangeListener {

	static final String REASON = "inviter_changed";

	private final InvitationRepository invitations;

	private final AuditLog audit;

	private final Clock clock;

	InvitationsOfChangedInviter(InvitationRepository invitations, AuditLog audit, Clock clock) {
		this.invitations = invitations;
		this.audit = audit;
		this.clock = clock;
	}

	@Override
	public void afterMembershipChange(UUID companyId, UUID userId, Role role, MembershipStatus status,
			UUID changedBy) {
		Instant now = clock.instant();
		for (Invitation invitation : invitations.findByCompanyIdAndInvitedByAndStatus(companyId, userId,
				InvitationStatus.PENDING)) {
			boolean stillAble = status == MembershipStatus.ACTIVE && InvitationRules.mayGrant(role, invitation.getRole());
			if (stillAble) {
				continue;
			}
			invitation.revoke(changedBy, now);
			invitations.saveAndFlush(invitation);
			audit.recordChange("invitation.revoked", AuditLog.Actor.user, changedBy, "invitation", invitation.getId(),
					companyId, "success", REASON, "PENDING", "REVOKED");
		}
	}

}
