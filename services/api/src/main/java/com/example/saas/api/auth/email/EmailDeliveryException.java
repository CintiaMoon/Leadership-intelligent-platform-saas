package com.example.saas.api.auth.email;

/** The message could not be handed to the mail server. The cause is not logged with the recipient. */
public class EmailDeliveryException extends RuntimeException {

	public EmailDeliveryException(Throwable cause) {
		super("Email delivery failed", cause);
	}

}
