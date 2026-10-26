package com.example.saas.api.tenant.ownership.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.outbox.OutboxEvent;
import com.example.saas.api.auth.outbox.OutboxHandler;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransfer;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransferRepository;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the email for one outbox event of an ownership transfer, when it is sent (D-185). The event carries identifiers
 * only; the company name and the address are read here, in a short read-only transaction, and nothing is sent inside it
 * (ADR-003). A retried send builds the same text. Nothing is built when the transfer is not in the state the event is
 * about (for example an "initiated" event that is delivered after the transfer was cancelled), or when the addressee's
 * account has been disabled.
 */
@Service
public class OwnershipTransferEmails {

	private final OwnershipTransferRepository transfers;

	private final CompanyRepository companies;

	private final UserAccountRepository users;

	private final AuthEmails emails;

	OwnershipTransferEmails(OwnershipTransferRepository transfers, CompanyRepository companies,
			UserAccountRepository users, AuthEmails emails) {
		this.transfers = transfers;
		this.companies = companies;
		this.users = users;
		this.emails = emails;
	}

	@Transactional(readOnly = true)
	public Optional<EmailMessage> prepare(String eventType, UUID companyId, UUID transferId, String addressee) {
		Optional<OwnershipTransfer> found = transfers.findByCompanyIdAndId(companyId, transferId);
		Optional<Company> company = companies.findById(companyId);
		if (found.isEmpty() || company.isEmpty() || !stateMatches(eventType, found.get().getStatus())) {
			return Optional.empty();
		}
		boolean toRecipient = OwnershipEvents.RECIPIENT.equals(addressee);
		UUID userId = toRecipient ? found.get().getRecipientId() : found.get().getInitiatedBy();
		Optional<UserAccount> account = users.findById(userId);
		if (account.isEmpty() || account.get().getStatus() != UserAccount.Status.ACTIVE) {
			return Optional.empty();
		}
		return Optional.of(emails.ownershipTransfer(eventType, toRecipient, account.get().getEmailOriginal(), companyId,
				company.get().getName()));
	}

	private static boolean stateMatches(String eventType, TransferStatus status) {
		return switch (eventType) {
			case OwnershipEvents.INITIATED -> status == TransferStatus.PENDING;
			case OwnershipEvents.COMPLETED -> status == TransferStatus.COMPLETED;
			case OwnershipEvents.CANCELLED -> status == TransferStatus.CANCELLED;
			case OwnershipEvents.EXPIRED -> status == TransferStatus.EXPIRED;
			default -> false;
		};
	}

	/** The four outbox handlers, one per event type. */
	static final class Handlers {

		private Handlers() {
		}

		abstract static class Base implements OutboxHandler {

			private final OwnershipTransferEmails emails;

			private final String eventType;

			Base(OwnershipTransferEmails emails, String eventType) {
				this.emails = emails;
				this.eventType = eventType;
			}

			@Override
			public String eventType() {
				return eventType;
			}

			@Override
			public Optional<EmailMessage> handle(OutboxEvent event) {
				return emails.prepare(eventType, UUID.fromString(event.payload().get("companyId")),
						UUID.fromString(event.payload().get("transferId")), event.payload().get("addressee"));
			}

		}

		@Component
		static class Initiated extends Base {

			Initiated(OwnershipTransferEmails emails) {
				super(emails, OwnershipEvents.INITIATED);
			}

		}

		@Component
		static class Completed extends Base {

			Completed(OwnershipTransferEmails emails) {
				super(emails, OwnershipEvents.COMPLETED);
			}

		}

		@Component
		static class Cancelled extends Base {

			Cancelled(OwnershipTransferEmails emails) {
				super(emails, OwnershipEvents.CANCELLED);
			}

		}

		@Component
		static class Expired extends Base {

			Expired(OwnershipTransferEmails emails) {
				super(emails, OwnershipEvents.EXPIRED);
			}

		}

	}

}
