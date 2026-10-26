package com.example.saas.api.tenant.ownership;

import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.domain.TransferRules;
import com.example.saas.api.tenant.ownership.domain.TransferRules.CompletionRefusal;
import com.example.saas.api.tenant.ownership.domain.TransferRules.Operation;
import com.example.saas.api.tenant.ownership.domain.TransferRules.RecipientProblem;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The ownership transfer rules as pure functions (SPEC-OWN; D-180 to D-183). */
class TransferRulesTest {

	@Test
	void onlyTheOwnerMayInitiateOrCancelAndEveryRoleMayBeTheRecipientWhoAccepts() {
		for (Role role : Role.values()) {
			boolean owner = role == Role.OWNER;
			assertThat(TransferRules.refusedWhateverTheRole(Operation.INITIATE, role))
				.as("%s initiates", role).isEqualTo(owner ? null : Permission.TRANSFER_OWNERSHIP);
			assertThat(TransferRules.refusedWhateverTheRole(Operation.CANCEL, role))
				.as("%s cancels", role).isEqualTo(owner ? null : Permission.CANCEL_OWNERSHIP_TRANSFER);
			assertThat(TransferRules.refusedWhateverTheRole(Operation.ACCEPT, role)).as("%s accepts", role).isNull();
		}
	}

	@Test
	void aRecipientMustBeSomeoneElseWithAnActiveMembershipAndAUsableAccount() {
		assertThat(TransferRules.checkRecipient(false, MembershipStatus.ACTIVE, true, true)).isEqualTo(RecipientProblem.NONE);
		assertThat(TransferRules.checkRecipient(true, MembershipStatus.ACTIVE, true, true)).isEqualTo(RecipientProblem.SELF);
		assertThat(TransferRules.checkRecipient(false, MembershipStatus.SUSPENDED, true, true))
			.isEqualTo(RecipientProblem.NOT_ACTIVE_MEMBER);
		assertThat(TransferRules.checkRecipient(false, MembershipStatus.REMOVED, true, true))
			.isEqualTo(RecipientProblem.NOT_ACTIVE_MEMBER);
		assertThat(TransferRules.checkRecipient(false, null, true, true)).isEqualTo(RecipientProblem.NOT_ACTIVE_MEMBER);
		assertThat(TransferRules.checkRecipient(false, MembershipStatus.ACTIVE, false, true))
			.as("account disabled").isEqualTo(RecipientProblem.ACCOUNT_NOT_USABLE);
		assertThat(TransferRules.checkRecipient(false, MembershipStatus.ACTIVE, true, false))
			.as("email not verified").isEqualTo(RecipientProblem.ACCOUNT_NOT_USABLE);
	}

	private static CompletionRefusal refusal(TransferStatus status, boolean overdue, CompanyStatus company, Role initiator,
			MembershipStatus initiatorStatus, RecipientProblem recipient) {
		return TransferRules.completionRefusal(status, overdue, company, initiator, initiatorStatus, recipient);
	}

	@Test
	void completionIsAllowedOnlyWhenEveryPreconditionHolds() {
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.ACTIVE, Role.OWNER, MembershipStatus.ACTIVE,
				RecipientProblem.NONE)).isEqualTo(CompletionRefusal.NONE);
	}

	@Test
	void everyOtherStateOfTheTransferIsRefused() {
		for (TransferStatus status : new TransferStatus[] { TransferStatus.COMPLETED, TransferStatus.CANCELLED,
				TransferStatus.EXPIRED }) {
			assertThat(refusal(status, false, CompanyStatus.ACTIVE, Role.OWNER, MembershipStatus.ACTIVE, RecipientProblem.NONE))
				.as(status.name()).isEqualTo(CompletionRefusal.NOT_PENDING);
		}
	}

	@Test
	void anOverdueTransferIsRefusedEvenThoughItsStoredStatusIsStillPending() {
		assertThat(refusal(TransferStatus.PENDING, true, CompanyStatus.ACTIVE, Role.OWNER, MembershipStatus.ACTIVE,
				RecipientProblem.NONE)).isEqualTo(CompletionRefusal.OVERDUE);
	}

	@Test
	void aCompanyThatIsNotActiveRefusesCompletion() {
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.PENDING_DELETION, Role.OWNER,
				MembershipStatus.ACTIVE, RecipientProblem.NONE)).isEqualTo(CompletionRefusal.COMPANY_NOT_ACTIVE);
	}

	@Test
	void theInitiatorMustStillBeTheActiveOwner() {
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.ACTIVE, Role.OWNER, MembershipStatus.SUSPENDED,
				RecipientProblem.NONE)).as("the Owner is suspended (flagged company)")
			.isEqualTo(CompletionRefusal.INITIATOR_NOT_ACTIVE_OWNER);
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.ACTIVE, Role.ADMIN, MembershipStatus.ACTIVE,
				RecipientProblem.NONE)).as("no longer the Owner").isEqualTo(CompletionRefusal.INITIATOR_NOT_ACTIVE_OWNER);
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.ACTIVE, null, null, RecipientProblem.NONE))
			.as("no live membership").isEqualTo(CompletionRefusal.INITIATOR_NOT_ACTIVE_OWNER);
	}

	@Test
	void theRecipientMustStillBeEligible() {
		for (RecipientProblem problem : new RecipientProblem[] { RecipientProblem.SELF, RecipientProblem.NOT_ACTIVE_MEMBER,
				RecipientProblem.ACCOUNT_NOT_USABLE }) {
			assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.ACTIVE, Role.OWNER, MembershipStatus.ACTIVE, problem))
				.as(problem.name()).isEqualTo(CompletionRefusal.RECIPIENT_NOT_ELIGIBLE);
		}
	}

	@Test
	void theChecksRunInTheOrderOfTheSpec() {
		// Everything wrong at once: the first check in the spec's order wins.
		assertThat(refusal(TransferStatus.EXPIRED, true, CompanyStatus.PENDING_DELETION, Role.ADMIN, MembershipStatus.REMOVED,
				RecipientProblem.SELF)).isEqualTo(CompletionRefusal.NOT_PENDING);
		assertThat(refusal(TransferStatus.PENDING, true, CompanyStatus.PENDING_DELETION, Role.ADMIN, MembershipStatus.REMOVED,
				RecipientProblem.SELF)).isEqualTo(CompletionRefusal.OVERDUE);
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.PENDING_DELETION, Role.ADMIN, MembershipStatus.REMOVED,
				RecipientProblem.SELF)).isEqualTo(CompletionRefusal.COMPANY_NOT_ACTIVE);
		assertThat(refusal(TransferStatus.PENDING, false, CompanyStatus.ACTIVE, Role.ADMIN, MembershipStatus.REMOVED,
				RecipientProblem.SELF)).isEqualTo(CompletionRefusal.INITIATOR_NOT_ACTIVE_OWNER);
	}

}
