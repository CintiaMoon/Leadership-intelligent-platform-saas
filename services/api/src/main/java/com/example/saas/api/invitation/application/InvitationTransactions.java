package com.example.saas.api.invitation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.domain.EmailAddress;
import com.example.saas.api.auth.outbox.OutboxRepository;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.invitation.domain.InvitationRules;
import com.example.saas.api.invitation.domain.InvitationRules.Decision;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.RecentAuthentication;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.PlanGate;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * The transactions behind {@link InvitationService}. Each one locks the company row first, then the invitation (D-125,
 * D-161), loads the caller's membership fresh under the lock, decides with {@link InvitationRules}, changes the rows,
 * writes the audit rows and queues the email, so a change either happens completely or not at all. No email is sent
 * and no network call is made inside (ADR-003): the outbox event is delivered after commit.
 */
@Component
class InvitationTransactions {

	/** An invitation is valid for 7 days from the latest create or resend (D-24, amended). */
	static final Duration LIFETIME = Duration.ofDays(7);

	static final String EMAIL_EVENT = "INVITATION_EMAIL";

	private final CompanyLocks companyLocks;

	private final InvitationLocks invitationLocks;

	private final MembershipRepository memberships;

	private final InvitationRepository invitations;

	private final RecentAuthentication recentAuthentication;

	private final OutboxRepository outbox;

	private final AuditLog audit;

	private final Clock clock;

	private final PlanGate planGate;

	InvitationTransactions(CompanyLocks companyLocks, InvitationLocks invitationLocks, MembershipRepository memberships,
			InvitationRepository invitations, RecentAuthentication recentAuthentication,
			OutboxRepository outbox, AuditLog audit, Clock clock, PlanGate planGate) {
		this.companyLocks = companyLocks;
		this.invitationLocks = invitationLocks;
		this.memberships = memberships;
		this.invitations = invitations;
		this.recentAuthentication = recentAuthentication;
		this.outbox = outbox;
		this.audit = audit;
		this.clock = clock;
		this.planGate = planGate;
	}

	@Transactional
	InvitationView create(AuthenticatedUser actor, UUID companyId, EmailAddress address, Role role) {
		Company company = companyLocks.lockCompany(companyId);
		Role actorRole = actorRole(actor, companyId);
		authorize(InvitationRules.decide(InvitationRules.Operation.CREATE, actorRole, role, company.getStatus()),
				InvitationRules.Operation.CREATE, actor, companyId, actorRole, role);
		if (memberships.hasLiveMemberWithEmail(companyId, address.normalized())) {
			throw new InvitationConflictException();
		}
		Instant now = clock.instant();
		Optional<Invitation> pending = invitations.findByCompanyIdAndEmailNormalizedAndStatus(companyId,
				address.normalized(), InvitationStatus.PENDING);
		if (pending.isPresent()) {
			Invitation old = pending.get();
			if (old.getExpiresAt().isAfter(now)) {
				throw new InvitationConflictException();
			}
			// An expired invitation must not block a new one (AC-INV-28). Flush now: the unique index covers pending rows
			// and Hibernate would otherwise run the insert before this update.
			old.expire(now);
			invitations.saveAndFlush(old);
			audit.recordChange("invitation.expired", AuditLog.Actor.system, null, "invitation", old.getId(), companyId,
					"success", "expired_on_new_invitation", "PENDING", "EXPIRED");
		}
		Invitation invitation = invitations.saveAndFlush(Invitation.issue(companyId, address.normalized(),
				address.original(), role, actor.userId(), now, now.plus(LIFETIME)));
		audit.recordChange("invitation.created", AuditLog.Actor.user, actor.userId(), "invitation", invitation.getId(),
				companyId, "success", null, null, role.name());
		queueEmail(companyId, invitation.getId());
		return view(invitation);
	}

	@Transactional
	InvitationView resend(AuthenticatedUser actor, UUID companyId, UUID invitationId) {
		Company company = companyLocks.lockCompany(companyId);
		Role actorRole = actorRole(actor, companyId);
		Invitation invitation = invitationLocks.lockInvitation(companyId, invitationId);
		authorize(InvitationRules.decide(InvitationRules.Operation.RESEND, actorRole, invitation.getRole(),
				company.getStatus()), InvitationRules.Operation.RESEND, actor, companyId, actorRole, invitation.getRole());
		if (invitation.getStatus() != InvitationStatus.PENDING) {
			throw new InvitationConflictException();
		}
		Instant now = clock.instant();
		invitation.reissue(now, now.plus(LIFETIME));
		invitations.saveAndFlush(invitation);
		audit.recordChange("invitation.resent", AuditLog.Actor.user, actor.userId(), "invitation", invitation.getId(),
				companyId, "success", null, null, invitation.getRole().name());
		queueEmail(companyId, invitation.getId());
		return view(invitation);
	}

	@Transactional
	void revoke(AuthenticatedUser actor, UUID companyId, UUID invitationId) {
		Company company = companyLocks.lockCompany(companyId);
		Role actorRole = actorRole(actor, companyId);
		Invitation invitation = invitationLocks.lockInvitation(companyId, invitationId);
		authorize(InvitationRules.decide(InvitationRules.Operation.REVOKE, actorRole, invitation.getRole(),
				company.getStatus()), InvitationRules.Operation.REVOKE, actor, companyId, actorRole, invitation.getRole());
		if (invitation.getStatus() != InvitationStatus.PENDING) {
			throw new InvitationConflictException();
		}
		invitation.revoke(actor.userId(), clock.instant());
		invitations.saveAndFlush(invitation);
		audit.recordChange("invitation.revoked", AuditLog.Actor.user, actor.userId(), "invitation", invitation.getId(),
				companyId, "success", null, "PENDING", "REVOKED");
	}

	/** The caller's role, loaded fresh now that the company lock serializes every change (never from before the lock). */
	private Role actorRole(AuthenticatedUser actor, UUID companyId) {
		return memberships.findByCompanyIdAndUserIdAndStatus(companyId, actor.userId(), MembershipStatus.ACTIVE)
			.map(Membership::getRole)
			.orElseThrow(CompanyNotFoundException::new);
	}

	private void authorize(Decision decision, InvitationRules.Operation operation, AuthenticatedUser actor,
			UUID companyId, Role actorRole, Role role) {
		if (decision.outcome() == InvitationRules.Outcome.FORBIDDEN) {
			throw new PermissionDeniedException(actor.userId(), companyId, decision.deniedPermission());
		}
		// Under the company lock, where the subscription state is stable (plan Q-10); before the request is called invalid.
		if (operation == InvitationRules.Operation.REVOKE) {
			planGate.requireRevokingInvitation(actor.userId(), companyId);
		}
		else {
			planGate.require(actor.userId(), companyId, InvitationRules.permissionFor(operation), actorRole);
		}
		switch (decision.outcome()) {
			case FORBIDDEN -> throw new IllegalStateException("handled above");
			case INVALID -> throw new ValidationFailedException(
					List.of(new FieldProblem("role", role == null ? "REQUIRED" : "INVALID")));
			case ALLOWED -> {
			}
		}
		if (decision.recentAuthenticationRequired()) {
			recentAuthentication.require(actor);
		}
	}

	private void queueEmail(UUID companyId, UUID invitationId) {
		// The event carries identifiers only. The token is minted when the email is built (D-146), never stored here.
		outbox.enqueue(EMAIL_EVENT, Map.of("companyId", companyId.toString(), "invitationId", invitationId.toString()),
				"invitation-email-" + UUID.randomUUID(), clock.instant());
	}

	static InvitationView view(Invitation invitation) {
		return new InvitationView(invitation.getId(), invitation.getEmailOriginal(), invitation.getRole(),
				invitation.getStatus(), invitation.getExpiresAt(), invitation.getCreatedAt(),
				invitation.getTokenHash() != null);
	}

}
