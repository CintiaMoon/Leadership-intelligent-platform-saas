package com.example.saas.api.tenant.ownership.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.outbox.OutboxEvent;
import com.example.saas.api.auth.outbox.OutboxHandler;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the email for the event the operator transfer script queues (D-142, AC-OWN-18). The event carries identifiers
 * only. The addressee is checked again when the email is built, in a short read-only transaction: the account is ACTIVE
 * and the person is still an ACTIVE member of the company, as the new Owner or as an Admin. So an address that stopped
 * qualifying, and in particular the previous Owner (a suspended member), is never written to. A retried send builds the
 * same text.
 */
@Component
public class OperatorTransferEmails implements OutboxHandler {

	public static final String EVENT_TYPE = "OWNERSHIP_TRANSFER_OPERATOR_COMPLETED";

	public static final String NEW_OWNER = "NEW_OWNER";

	public static final String ADMIN = "ADMIN";

	private final CompanyRepository companies;

	private final MembershipRepository memberships;

	private final UserAccountRepository users;

	private final AuthEmails emails;

	OperatorTransferEmails(CompanyRepository companies, MembershipRepository memberships, UserAccountRepository users,
			AuthEmails emails) {
		this.companies = companies;
		this.memberships = memberships;
		this.users = users;
		this.emails = emails;
	}

	@Override
	public String eventType() {
		return EVENT_TYPE;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<EmailMessage> handle(OutboxEvent event) {
		UUID companyId = UUID.fromString(event.payload().get("companyId"));
		UUID userId = UUID.fromString(event.payload().get("userId"));
		boolean newOwner = NEW_OWNER.equals(event.payload().get("addressee"));
		Optional<Membership> seat = memberships.findByCompanyIdAndUserIdAndStatus(companyId, userId,
				MembershipStatus.ACTIVE);
		if (seat.isEmpty() || !qualifies(seat.get().getRole(), newOwner)) {
			return Optional.empty();
		}
		Optional<UserAccount> account = users.findById(userId);
		if (account.isEmpty() || account.get().getStatus() != UserAccount.Status.ACTIVE) {
			return Optional.empty();
		}
		return companies.findById(companyId).map(company -> emails.ownershipOperatorTransfer(newOwner,
				account.get().getEmailOriginal(), companyId, company.getName()));
	}

	private static boolean qualifies(Role role, boolean newOwner) {
		return newOwner ? role == Role.OWNER : role == Role.ADMIN || role == Role.OWNER;
	}

}
