package com.example.saas.api.invitation.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.domain.EmailAddress;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.invitation.domain.InvitationRules;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PlanGate;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.saas.api.auth.application.AuthRateLimits.requireWithin;

/**
 * Creating, resending, revoking and listing invitations (SPEC-INV). This class does everything that must happen
 * <em>before</em> any lock: it answers an outsider with 404 and refuses an Agent or Viewer without queueing for the
 * company lock or using up the company's allowance (D-137, D-148), and it applies the send limits (AC-INV-23), counted
 * before the request is validated. The changes themselves are in {@link InvitationTransactions}.
 */
@Service
public class InvitationService {

	private final InvitationTransactions transactions;

	private final MembershipRepository memberships;

	private final InvitationRepository invitations;

	private final RateLimiter rateLimiter;

	private final Clock clock;

	private final PlanGate planGate;

	InvitationService(InvitationTransactions transactions, MembershipRepository memberships,
			InvitationRepository invitations, RateLimiter rateLimiter, Clock clock, PlanGate planGate) {
		this.transactions = transactions;
		this.memberships = memberships;
		this.invitations = invitations;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
		this.planGate = planGate;
	}

	/**
	 * @throws CompanyNotFoundException the caller is not an ACTIVE member (404)
	 * @throws PermissionDeniedException the caller's role cannot do this (403, audited by the handler)
	 * @throws com.example.saas.api.ratelimit.RateLimitedException a send limit was exceeded (429)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 * @throws ValidationFailedException the address or the role is invalid (400)
	 * @throws InvitationConflictException the address is already a member, or an invitation is already pending (409)
	 * @throws com.example.saas.api.security.ReauthenticationRequiredException inviting an Admin with a stale login (403)
	 */
	public InvitationView create(AuthenticatedUser actor, UUID companyId, String rawEmail, Role role) {
		refuseEarly(InvitationRules.Operation.CREATE, actor, companyId);
		countSend(actor, companyId);
		EmailAddress address = EmailAddress.parse(rawEmail)
			.orElseThrow(() -> new ValidationFailedException(List.of(new FieldProblem("email", "INVALID_EMAIL"))));
		requireRecipientAllowance(companyId, address.normalized());
		InvitationView created = transactions.create(actor, companyId, address, role);
		countRecipient(companyId, address.normalized());
		return created;
	}

	/** Issues the invitation again: the old token stops working at once and a new one is minted at delivery. */
	public InvitationView resend(AuthenticatedUser actor, UUID companyId, UUID invitationId) {
		refuseEarly(InvitationRules.Operation.RESEND, actor, companyId);
		countSend(actor, companyId);
		// The address is read as a plain value before the transaction, so the counter store is not called while the
		// company row is locked. A foreign or unknown ID has no address here and gets its 404 from the transaction.
		Optional<String> recipient = invitations.findEmailNormalizedByCompanyIdAndId(companyId, invitationId);
		if (recipient.isPresent()) {
			requireRecipientAllowance(companyId, recipient.get());
		}
		InvitationView resent = transactions.resend(actor, companyId, invitationId);
		recipient.ifPresent(address -> countRecipient(companyId, address));
		return resent;
	}

	public void revoke(AuthenticatedUser actor, UUID companyId, UUID invitationId) {
		refuseEarly(InvitationRules.Operation.REVOKE, actor, companyId);
		transactions.revoke(actor, companyId, invitationId);
	}

	/** The pending, unexpired invitations. The caller was already authorized for "View member list". */
	@Transactional(readOnly = true)
	public List<InvitationView> listPending(UUID companyId) {
		return invitations
			.findByCompanyIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(companyId, InvitationStatus.PENDING,
					clock.instant())
			.stream()
			.map(InvitationTransactions::view)
			.toList();
	}

	private void refuseEarly(InvitationRules.Operation operation, AuthenticatedUser actor, UUID companyId) {
		Role role = memberships
			.findRoleByCompanyIdAndUserIdAndStatus(companyId, actor.userId(), MembershipStatus.ACTIVE)
			.orElseThrow(CompanyNotFoundException::new);
		Permission refused = InvitationRules.refusedWhateverTheRole(operation, role);
		if (refused != null) {
			throw new PermissionDeniedException(actor.userId(), companyId, refused);
		}
		// Before any lock and before anything is counted: a restricted company is refused without using an allowance.
		if (operation == InvitationRules.Operation.REVOKE) {
			planGate.requireRevokingInvitation(actor.userId(), companyId);
		}
		else {
			planGate.require(actor.userId(), companyId, InvitationRules.permissionFor(operation), role);
		}
	}

	/**
	 * Refuses when this recipient's allowance is used up, in this company or across companies (D-164). It only reads, so
	 * a request that is then rejected (already a member, already pending) does not use up an allowance.
	 */
	private void requireRecipientAllowance(UUID companyId, String normalized) {
		requireWithin(rateLimiter.current(InvitationRateLimits.PER_RECIPIENT_IN_COMPANY, companyId + ":" + normalized), clock);
		requireWithin(rateLimiter.current(InvitationRateLimits.PER_RECIPIENT_ANYWHERE, normalized), clock);
	}

	/**
	 * Counts one email queued for this recipient, after the request committed. The read and the count are not one atomic
	 * step, so simultaneous sends can slightly exceed a limit; the limits are against abuse, not exact quotas. If the
	 * counter store fails here the invitation already exists, so the failure is not turned into an error response.
	 */
	private void countRecipient(UUID companyId, String normalized) {
		try {
			rateLimiter.hit(InvitationRateLimits.PER_RECIPIENT_IN_COMPANY, companyId + ":" + normalized);
			rateLimiter.hit(InvitationRateLimits.PER_RECIPIENT_ANYWHERE, normalized);
		}
		catch (RateLimitStoreUnavailableException unavailable) {
			// Fail closed applies to the next request, whose read will fail too.
		}
	}

	/** One send against the inviter's and the company's allowance, before the request is validated. */
	private void countSend(AuthenticatedUser actor, UUID companyId) {
		requireWithin(rateLimiter.hit(InvitationRateLimits.PER_INVITER, actor.userId().toString()), clock);
		requireWithin(rateLimiter.hit(InvitationRateLimits.PER_COMPANY, companyId.toString()), clock);
	}

}
