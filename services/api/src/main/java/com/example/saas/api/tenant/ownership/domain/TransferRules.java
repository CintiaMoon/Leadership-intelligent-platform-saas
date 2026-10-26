package com.example.saas.api.tenant.ownership.domain;

import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.Grant;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;

/**
 * The rules for ownership transfers (SPEC-OWN; D-180 to D-183) as pure functions over values the caller has loaded under
 * the locks, so every rule can be tested without a database. Permission comes from {@link CompanyPolicy}, so the matrix
 * stays in one place; this class adds recipient eligibility and the preconditions of completion.
 */
public final class TransferRules {

	private TransferRules() {
	}

	public enum Operation {

		INITIATE, CANCEL, ACCEPT

	}

	/**
	 * The matrix row that refuses this role whatever the transfer is, or null if the role might be allowed. Everybody but
	 * the Owner is refused before any lock is taken, so a member with no such permission cannot queue for the company
	 * lock and starve the people who do (D-137, D-182). Accepting is allowed for every role in the matrix; only the named
	 * recipient may do it, which the service checks.
	 */
	public static Permission refusedWhateverTheRole(Operation operation, Role actorRole) {
		Permission needed = switch (operation) {
			case INITIATE -> Permission.TRANSFER_OWNERSHIP;
			case CANCEL -> Permission.CANCEL_OWNERSHIP_TRANSFER;
			case ACCEPT -> Permission.ACCEPT_OWNERSHIP_TRANSFER;
		};
		return CompanyPolicy.grant(actorRole, needed) == Grant.DENIED ? needed : null;
	}

	/** Why a member cannot receive the ownership, if they cannot (D-180, AC-OWN-08, AC-OWN-19). */
	public enum RecipientProblem {

		NONE, SELF, NOT_ACTIVE_MEMBER, ACCOUNT_NOT_USABLE

	}

	/**
	 * @param self the recipient is the person handing the ownership over
	 * @param membershipStatus the recipient's membership status, or null if they have no live membership
	 * @param accountActive the recipient's account is ACTIVE (not disabled by an operator)
	 * @param emailVerified the recipient's email is verified
	 */
	public static RecipientProblem checkRecipient(boolean self, MembershipStatus membershipStatus, boolean accountActive,
			boolean emailVerified) {
		if (self) {
			return RecipientProblem.SELF;
		}
		if (membershipStatus != MembershipStatus.ACTIVE) {
			return RecipientProblem.NOT_ACTIVE_MEMBER;
		}
		if (!accountActive || !emailVerified) {
			return RecipientProblem.ACCOUNT_NOT_USABLE;
		}
		return RecipientProblem.NONE;
	}

	/** Why a transfer cannot be completed now (AC-OWN-03, 08, 14, 15, 16). */
	public enum CompletionRefusal {

		NONE, NOT_PENDING, OVERDUE, COMPANY_NOT_ACTIVE, INITIATOR_NOT_ACTIVE_OWNER, RECIPIENT_NOT_ELIGIBLE

	}

	/**
	 * The checks of the completion transaction, in the order of the spec, over values loaded under the locks.
	 *
	 * @param status the transfer's stored status
	 * @param overdue the transfer ran past its expiry (it counts as expired even if the sweep has not marked it)
	 * @param initiatorRole the initiator's membership role, or null if they have no live membership
	 * @param initiatorStatus the initiator's membership status, or null
	 * @param recipientProblem the result of {@link #checkRecipient}, recomputed under the lock
	 */
	public static CompletionRefusal completionRefusal(TransferStatus status, boolean overdue, CompanyStatus companyStatus,
			Role initiatorRole, MembershipStatus initiatorStatus, RecipientProblem recipientProblem) {
		if (status != TransferStatus.PENDING) {
			return CompletionRefusal.NOT_PENDING;
		}
		if (overdue) {
			return CompletionRefusal.OVERDUE;
		}
		if (companyStatus != CompanyStatus.ACTIVE) {
			return CompletionRefusal.COMPANY_NOT_ACTIVE;
		}
		if (initiatorRole != Role.OWNER || initiatorStatus != MembershipStatus.ACTIVE) {
			return CompletionRefusal.INITIATOR_NOT_ACTIVE_OWNER;
		}
		if (recipientProblem != RecipientProblem.NONE) {
			return CompletionRefusal.RECIPIENT_NOT_ELIGIBLE;
		}
		return CompletionRefusal.NONE;
	}

}
