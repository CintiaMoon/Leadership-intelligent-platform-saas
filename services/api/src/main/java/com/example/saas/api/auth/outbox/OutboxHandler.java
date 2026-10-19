package com.example.saas.api.auth.outbox;

import java.util.Optional;

import com.example.saas.api.auth.email.EmailMessage;

/**
 * Turns one outbox event into the email to send. A handler may read and write the database in its own
 * short transaction (for example to mint a token) but must not send anything itself: the worker sends the
 * returned message after that transaction has committed. Handlers must be safe to run again for the same event.
 */
public interface OutboxHandler {

	String eventType();

	/** The email to send, or empty if there is nothing to do (for example the account no longer qualifies). */
	Optional<EmailMessage> handle(OutboxEvent event);

}
