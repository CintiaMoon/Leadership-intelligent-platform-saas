package com.example.saas.api.invitation.application;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.application.TokenInvalidException;
import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.invitation.domain.InvitationRules;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.tenant.application.ActiveMemberLimit;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.PlanGate;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.MemberLimitReachedException;
import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accept transaction (D-152, D-153). The company is found from the token hash as a plain value, then locked; the
 * invitation is locked after it and re-read; everything is checked under the locks, so a concurrent resend, revoke,
 * accept, membership change or deletion request cannot slip between the checks and the write. Every reason the
 * invitation itself is unusable throws {@link TokenInvalidException}, so the caller sees one answer (AC-INV-22). Only
 * after the signed-in address matched can the answer differ ({@link InvitationConflictException}: already a member or a
 * restricted subscription state; {@link MemberLimitReachedException}: no room), which tells the rightful invitee
 * something they are entitled to know.
 */
@Component
class InvitationAcceptTransactions {

	private final InvitationRepository invitations;

	private final CompanyLocks companyLocks;

	private final InvitationLocks invitationLocks;

	private final MembershipRepository memberships;

	private final UserAccountRepository users;

	private final ActiveMemberLimit memberLimit;

	private final PlanGate planGate;

	private final AuditLog audit;

	private final Clock clock;

	InvitationAcceptTransactions(InvitationRepository invitations, CompanyLocks companyLocks,
			InvitationLocks invitationLocks, MembershipRepository memberships, UserAccountRepository users,
			ActiveMemberLimit memberLimit, PlanGate planGate, AuditLog audit, Clock clock) {
		this.invitations = invitations;
		this.companyLocks = companyLocks;
		this.invitationLocks = invitationLocks;
		this.memberships = memberships;
		this.users = users;
		this.memberLimit = memberLimit;
		this.planGate = planGate;
		this.audit = audit;
		this.clock = clock;
	}

	/** @throws TokenInvalidException for every reason the invitation cannot be used by this signed-in user */
	@Transactional
	AcceptedInvitation accept(UUID userId, String rawToken) {
		String hash = TokenCodec.hash(rawToken);
		UUID companyId = invitations.findCompanyIdByTokenHash(hash).orElseThrow(TokenInvalidException::new);
		Company company = lockedCompany(companyId);
		UUID invitationId = invitations.findIdByCompanyIdAndTokenHash(companyId, hash)
			.orElseThrow(TokenInvalidException::new);
		Invitation invitation = lockedInvitation(companyId, invitationId);
		Instant now = clock.instant();

		if (invitation.getStatus() != InvitationStatus.PENDING || invitation.getTokenHash() == null
				|| !MessageDigest.isEqual(invitation.getTokenHash().getBytes(StandardCharsets.UTF_8),
						hash.getBytes(StandardCharsets.UTF_8))
				|| !invitation.getExpiresAt().isAfter(now) || company.getStatus() != CompanyStatus.ACTIVE) {
			throw new TokenInvalidException();
		}
		UserAccount user = users.findById(userId).orElseThrow(TokenInvalidException::new);
		if (!user.isEmailVerified() || user.getStatus() != UserAccount.Status.ACTIVE
				|| !user.getEmailNormalized().equals(invitation.getEmailNormalized())) {
			throw new TokenInvalidException();
		}
		if (!inviterMayStillGrant(companyId, invitation, company.getStatus())) {
			throw new TokenInvalidException();
		}

		// The address matched: from here the invitee is told why it cannot be used. Nothing has changed yet.
		if (memberships.hasLiveMembership(companyId, userId)) {
			throw new InvitationConflictException();
		}
		// Accepting adds a member, so it needs a running subscription, checked under the lock together with the limit
		// (plan Q-10, Q-11). The invitee is not a member and is not told the state: a restricted state is the generic
		// conflict. A full company is MEMBER_LIMIT_REACHED (plan Q-11, Q-18, D-212), said only to an invitee whose
		// address matched.
		if (!CompanyPolicy.stateAllowsInvitationAcceptance(planGate.state(companyId))) {
			throw new InvitationConflictException();
		}
		if (!memberLimit.hasRoomForAnotherActiveMember(companyId)) {
			throw new MemberLimitReachedException();
		}

		Membership membership = memberships.saveAndFlush(Membership.activate(companyId, userId, invitation.getRole(), now));
		invitation.accept(userId, now);
		invitations.saveAndFlush(invitation);
		audit.recordChange("invitation.accepted", AuditLog.Actor.user, userId, "invitation", invitation.getId(), companyId,
				"success", null, "PENDING", "ACCEPTED");
		audit.recordChange("membership.created", AuditLog.Actor.user, userId, "membership", membership.getId(), companyId,
				"success", null, null, invitation.getRole().name());
		return new AcceptedInvitation(companyId, company.getName(), invitation.getRole());
	}

	/** The inviter must still have an ACTIVE account and an ACTIVE membership that may hand out this role now (D-62, AC-INV-24). */
	private boolean inviterMayStillGrant(UUID companyId, Invitation invitation, CompanyStatus companyStatus) {
		Optional<Membership> inviter = memberships.findByCompanyIdAndUserIdAndStatus(companyId, invitation.getInvitedBy(),
				MembershipStatus.ACTIVE);
		return inviter.isPresent() && InvitationRules.mayGrant(inviter.get().getRole(), invitation.getRole())
				&& CompanyPolicy.permits(inviter.get().getRole(), Permission.INVITE_MEMBERS, companyStatus, null)
				// A disabled account keeps its memberships (D-141), so the account itself must be checked as well.
				&& users.findById(invitation.getInvitedBy()).map(u -> u.getStatus() == UserAccount.Status.ACTIVE).orElse(false);
	}

	private Company lockedCompany(UUID companyId) {
		try {
			return companyLocks.lockCompany(companyId);
		}
		catch (CompanyNotFoundException gone) {
			throw new TokenInvalidException();
		}
	}

	private Invitation lockedInvitation(UUID companyId, UUID invitationId) {
		try {
			return invitationLocks.lockInvitation(companyId, invitationId);
		}
		catch (CompanyNotFoundException gone) {
			throw new TokenInvalidException();
		}
	}

}
