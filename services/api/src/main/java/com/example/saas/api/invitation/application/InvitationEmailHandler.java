package com.example.saas.api.invitation.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.outbox.OutboxEvent;
import com.example.saas.api.auth.outbox.OutboxHandler;
import org.springframework.stereotype.Component;

/** Delivers the invitation email for an outbox event (ADR-003). Safe to run again for the same event. */
@Component
class InvitationEmailHandler implements OutboxHandler {

	private final InvitationEmails emails;

	InvitationEmailHandler(InvitationEmails emails) {
		this.emails = emails;
	}

	@Override
	public String eventType() {
		return InvitationTransactions.EMAIL_EVENT;
	}

	@Override
	public Optional<EmailMessage> handle(OutboxEvent event) {
		return emails.prepare(UUID.fromString(event.payload().get("companyId")),
				UUID.fromString(event.payload().get("invitationId")));
	}

}
