package com.example.saas.api.tenant.ownership.application;

import java.time.Clock;
import java.util.UUID;

import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.RecentAuthentication;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PlanGate;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.domain.TransferRules;
import com.example.saas.api.tenant.ownership.domain.TransferRules.Operation;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransfer;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransferRepository;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.example.saas.api.auth.application.AuthRateLimits.requireWithin;

/**
 * Ownership transfers (SPEC-OWN): initiate, cancel, accept, read, and the hook a deletion request calls. This class does
 * everything that must happen <em>before</em> any lock: it answers an outsider with 404 and refuses a role that can
 * never do the operation without queueing for the company lock (D-137, D-182), answers a person who is not the named
 * recipient with 404, checks recent authentication (D-186), and applies the initiation limit (D-187). The changes
 * themselves are in {@link OwnershipTransferTransactions}.
 */
@Service
public class OwnershipTransferService {

	private final OwnershipTransferTransactions transactions;

	private final MembershipRepository memberships;

	private final OwnershipTransferRepository transfers;

	private final RecentAuthentication recentAuthentication;

	private final RateLimiter rateLimiter;

	private final Clock clock;

	private final PlanGate planGate;

	OwnershipTransferService(OwnershipTransferTransactions transactions, MembershipRepository memberships,
			OwnershipTransferRepository transfers, RecentAuthentication recentAuthentication, RateLimiter rateLimiter,
			Clock clock, PlanGate planGate) {
		this.transactions = transactions;
		this.memberships = memberships;
		this.transfers = transfers;
		this.recentAuthentication = recentAuthentication;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
		this.planGate = planGate;
	}

	/**
	 * @param recipientMembershipId the membership of the member who is asked to become the Owner
	 * @throws CompanyNotFoundException the caller is not an ACTIVE member, or the recipient's membership is not in the company (404)
	 * @throws PermissionDeniedException the caller is not the Owner, or the company is not ACTIVE (403, audited by the handler)
	 * @throws com.example.saas.api.security.ReauthenticationRequiredException the login is older than 10 minutes (403)
	 * @throws com.example.saas.api.ratelimit.RateLimitedException five transfers were already initiated today (429)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 * @throws com.example.saas.api.common.ValidationFailedException the recipient is the caller (400)
	 * @throws OwnershipConflictException a transfer is already pending, or the recipient is not eligible (409)
	 */
	public TransferView initiate(AuthenticatedUser actor, UUID companyId, UUID recipientMembershipId) {
		refuseEarly(Operation.INITIATE, actor, companyId);
		recentAuthentication.require(actor);
		requireWithin(rateLimiter.current(OwnershipRateLimits.INITIATED_PER_COMPANY, companyId.toString()), clock);
		TransferView created = transactions.initiate(actor, companyId, recipientMembershipId);
		countInitiated(companyId);
		return created;
	}

	/** Only the initiator may cancel. A transfer that is not pending, or is overdue, gives 409. */
	public TransferView cancel(AuthenticatedUser actor, UUID companyId, UUID transferId) {
		refuseEarly(Operation.CANCEL, actor, companyId);
		return transactions.cancel(actor, companyId, transferId);
	}

	/**
	 * Only the named recipient may accept, with a login or step-up within 10 minutes. Everything the completion checks
	 * (state, expiry, company, initiator, recipient) is checked again under the locks.
	 */
	public TransferView accept(AuthenticatedUser actor, UUID companyId, UUID transferId) {
		refuseEarly(Operation.ACCEPT, actor, companyId);
		// A person who is not the recipient is not queued for any lock: the transfer does not exist for them (AC-OWN-22).
		OwnershipTransfer transfer = transfers.findByCompanyIdAndId(companyId, transferId)
			.orElseThrow(CompanyNotFoundException::new);
		if (!transfer.getRecipientId().equals(actor.userId())) {
			throw new CompanyNotFoundException();
		}
		recentAuthentication.require(actor);
		return transactions.accept(actor, companyId, transferId);
	}

	/** The company's pending transfer, for its initiator or its recipient; 404 for anybody else or when there is none. */
	public TransferView current(AuthenticatedUser caller, UUID companyId) {
		requireActiveMember(caller, companyId);
		return transactions.current(caller.userId(), companyId);
	}

	/** One transfer of the company in any state, for its initiator or its recipient. */
	public TransferView get(AuthenticatedUser caller, UUID companyId, UUID transferId) {
		requireActiveMember(caller, companyId);
		return transactions.get(caller.userId(), companyId, transferId);
	}

	/**
	 * The hook for a deletion request (D-31, AC-OWN-23): cancels the company's pending transfer with an audit entry and
	 * the cancellation emails. Call it inside the deletion request's transaction, after it took the company lock.
	 *
	 * @return whether there was a pending transfer
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean cancelPendingForDeletion(UUID companyId, UUID actorUserId) {
		return transactions.cancelPendingForDeletion(companyId, actorUserId);
	}

	private void refuseEarly(Operation operation, AuthenticatedUser actor, UUID companyId) {
		Role role = requireActiveMember(actor, companyId);
		Permission refused = TransferRules.refusedWhateverTheRole(operation, role);
		if (refused != null) {
			throw new PermissionDeniedException(actor.userId(), companyId, refused);
		}
		planGate.require(actor.userId(), companyId, TransferRules.permissionFor(operation), role);
	}

	private Role requireActiveMember(AuthenticatedUser actor, UUID companyId) {
		return memberships.findRoleByCompanyIdAndUserIdAndStatus(companyId, actor.userId(), MembershipStatus.ACTIVE)
			.orElseThrow(CompanyNotFoundException::new);
	}

	/**
	 * Counts one transfer initiated, after the request committed. The read and the count are not one atomic step, so
	 * simultaneous initiations can slightly exceed the limit (the unique index still allows only one pending transfer).
	 * If the counter store fails here the transfer already exists, so the failure is not turned into an error response.
	 */
	private void countInitiated(UUID companyId) {
		try {
			rateLimiter.hit(OwnershipRateLimits.INITIATED_PER_COMPANY, companyId.toString());
		}
		catch (RateLimitStoreUnavailableException unavailable) {
			// Fail closed applies to the next request, whose read will fail too.
		}
	}

}
