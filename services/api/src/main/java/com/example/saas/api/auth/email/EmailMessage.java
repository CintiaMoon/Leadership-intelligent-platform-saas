package com.example.saas.api.auth.email;

/** A plain-text email. The body may contain a one-time link, so messages are never logged. */
public record EmailMessage(String to, String subject, String body) {

	@Override
	public String toString() {
		return "EmailMessage[subject=" + subject + "]";
	}

}
