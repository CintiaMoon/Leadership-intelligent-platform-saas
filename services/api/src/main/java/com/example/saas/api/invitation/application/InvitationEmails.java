package com.example.saas.api.invitation.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.persistence.Company;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mints the invitation token at delivery and builds the email that carries it (D-146). The raw token exists only in the
 * returned message: the database gets its hash, and the outbox event carries identifiers only. Runs in its own short
 * transaction under the same locks as every other change (company row, then invitation), so it cannot race a revoke, a
 * resend, an accept or a membership change. A retried delivery mints a new token and the previous one stops working.
 */
@Service
public class InvitationEmails {

	private final CompanyLocks companyLocks;

	private final InvitationLocks invitationLocks;

	private final InvitationRepository invitations;

	private final AuthEmails emails;

	private final TransactionTemplate transaction;

	private final Clock clock;

	InvitationEmails(CompanyLocks companyLocks, InvitationLocks invitationLocks, InvitationRepository invitations,
			AuthEmails emails, TransactionTemplate transaction, Clock clock) {
		this.companyLocks = companyLocks;
		this.invitationLocks = invitationLocks;
		this.invitations = invitations;
		this.emails = emails;
		this.transaction = transaction;
		this.clock = clock;
	}

	/**
	 * The email to send, or empty if there is nothing to send: the invitation is gone, no longer pending (revoked,
	 * accepted, expired), past its expiry, or its company is no longer active.
	 */
	public Optional<EmailMessage> prepare(UUID companyId, UUID invitationId) {
		try {
			return transaction.execute(status -> {
				Company company = companyLocks.lockCompany(companyId);
				Invitation invitation = invitationLocks.lockInvitation(companyId, invitationId);
				if (company.getStatus() != CompanyStatus.ACTIVE || invitation.getStatus() != InvitationStatus.PENDING
						|| !invitation.getExpiresAt().isAfter(clock.instant())) {
					return Optional.empty();
				}
				String token = TokenCodec.newToken();
				invitation.deliverWith(TokenCodec.hash(token), clock.instant());
				invitations.saveAndFlush(invitation);
				return Optional.of(emails.invitation(invitation.getEmailOriginal(), company.getName(),
						invitation.getRole().name().toLowerCase(), invitation.getExpiresAt(), token));
			});
		}
		catch (CompanyNotFoundException gone) {
			// The company or the invitation no longer exists (for example it was purged): nothing to deliver.
			return Optional.empty();
		}
	}

}
