package com.example.saas.api.tenant.deletion.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the email for one outbox event of a company deletion, when it is sent (plan Q-09, Q-27). The event carries the
 * company and the person only. Everything is checked again then, in a short read-only transaction, and nothing is sent
 * inside it (ADR-003): the company is in the state the event is about (a "requested" email delivered after the
 * cancellation is dropped, and the other way round), the person is still an ACTIVE member whose role fits the kind of
 * email, and the account is ACTIVE. A retried send builds the same text.
 */
@Service
public class DeletionEmails {

	private final CompanyRepository companies;

	private final MembershipRepository memberships;

	private final UserAccountRepository users;

	private final AuthEmails emails;

	DeletionEmails(CompanyRepository companies, MembershipRepository memberships, UserAccountRepository users,
			AuthEmails emails) {
		this.companies = companies;
		this.memberships = memberships;
		this.users = users;
		this.emails = emails;
	}

	@Transactional(readOnly = true)
	public Optional<EmailMessage> prepare(String eventType, UUID companyId, UUID userId) {
		Optional<Company> company = companies.findById(companyId);
		if (company.isEmpty() || !stateMatches(eventType, company.get())) {
			return Optional.empty();
		}
		Optional<Membership> seat = memberships.findByCompanyIdAndUserIdAndStatus(companyId, userId, MembershipStatus.ACTIVE);
		if (seat.isEmpty() || !roleFits(eventType, seat.get().getRole())) {
			return Optional.empty();
		}
		Optional<UserAccount> account = users.findById(userId);
		if (account.isEmpty() || account.get().getStatus() != UserAccount.Status.ACTIVE) {
			return Optional.empty();
		}
		return Optional.of(emails.companyDeletion(eventType, account.get().getEmailOriginal(), companyId,
				company.get().getName(), company.get().getPurgeAt()));
	}

	private static boolean stateMatches(String eventType, Company company) {
		return switch (eventType) {
			case DeletionEvents.REQUESTED, DeletionEvents.NOTICE -> company.getStatus() != CompanyStatus.ACTIVE
					&& company.getPurgeAt() != null;
			case DeletionEvents.CANCELLED -> company.getStatus() == CompanyStatus.ACTIVE;
			default -> false;
		};
	}

	private static boolean roleFits(String eventType, Role role) {
		boolean decisionMaker = role == Role.OWNER || role == Role.ADMIN;
		return DeletionEvents.NOTICE.equals(eventType) ? !decisionMaker : decisionMaker;
	}

}
