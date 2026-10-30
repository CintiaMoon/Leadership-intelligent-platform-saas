package com.example.saas.api.tenant.deletion.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.outbox.OutboxEvent;
import com.example.saas.api.auth.outbox.OutboxHandler;
import org.springframework.stereotype.Component;

/** The three outbox handlers of company deletion; each hands its event to {@link DeletionEmails}. */
final class DeletionEmailHandlers {

	private DeletionEmailHandlers() {
	}

	abstract static class Base implements OutboxHandler {

		private final DeletionEmails emails;

		private final String eventType;

		Base(DeletionEmails emails, String eventType) {
			this.emails = emails;
			this.eventType = eventType;
		}

		@Override
		public String eventType() {
			return eventType;
		}

		@Override
		public Optional<EmailMessage> handle(OutboxEvent event) {
			UUID companyId = UUID.fromString(event.payload().get("companyId"));
			UUID userId = UUID.fromString(event.payload().get("userId"));
			return emails.prepare(eventType, companyId, userId);
		}

	}

	@Component
	static class Requested extends Base {

		Requested(DeletionEmails emails) {
			super(emails, DeletionEvents.REQUESTED);
		}

	}

	@Component
	static class Cancelled extends Base {

		Cancelled(DeletionEmails emails) {
			super(emails, DeletionEvents.CANCELLED);
		}

	}

	@Component
	static class Notice extends Base {

		Notice(DeletionEmails emails) {
			super(emails, DeletionEvents.NOTICE);
		}

	}

}
