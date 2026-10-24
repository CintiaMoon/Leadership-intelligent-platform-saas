package com.example.saas.api.invitation.application;

import java.util.UUID;

import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lock for an invitation, taken after the company row (D-125, D-161): every create, resend, revoke and accept locks
 * the company first, then the invitation, inside the caller's transaction, so they cannot deadlock each other or the
 * membership changes of slice 003. A lock not granted within the timeout fails with a locking exception (503).
 */
@Component
public class InvitationLocks {

	private final InvitationRepository invitations;

	private final CompanyLocks companyLocks;

	InvitationLocks(InvitationRepository invitations, CompanyLocks companyLocks) {
		this.invitations = invitations;
		this.companyLocks = companyLocks;
	}

	/**
	 * Locks one invitation of the company. An invitation of another company is reported as missing, exactly like an
	 * unknown one, so a path cannot reach across tenants (AC-INV-06, AC-TEN-08).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Invitation lockInvitation(UUID companyId, UUID invitationId) {
		companyLocks.limitWait();
		return invitations.lockByCompanyIdAndId(companyId, invitationId).orElseThrow(CompanyNotFoundException::new);
	}

}
