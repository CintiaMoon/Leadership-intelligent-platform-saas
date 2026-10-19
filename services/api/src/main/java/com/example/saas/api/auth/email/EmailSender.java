package com.example.saas.api.auth.email;

/** Delivery port. Implementations must not log the message body or recipient (the body holds links). */
public interface EmailSender {

	/** Sends the message or throws; the outbox retries on failure. Never call inside a database transaction. */
	void send(EmailMessage message);

}
