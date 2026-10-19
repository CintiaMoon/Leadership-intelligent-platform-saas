package com.example.saas.api.auth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.email.EmailSender;

/** Captures sent messages so tests can read the link a user would receive. */
public class RecordingEmailSender implements EmailSender {

	private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

	private volatile RuntimeException failure;

	@Override
	public void send(EmailMessage message) {
		RuntimeException toThrow = failure;
		if (toThrow != null) {
			throw toThrow;
		}
		sent.add(message);
	}

	public List<EmailMessage> sent() {
		return List.copyOf(sent);
	}

	public List<EmailMessage> sentTo(String address) {
		return sent.stream().filter(message -> message.to().equalsIgnoreCase(address)).toList();
	}

	public void failWith(RuntimeException exception) {
		this.failure = exception;
	}

	public void clear() {
		sent.clear();
		failure = null;
	}

	public static Predicate<EmailMessage> subjectContains(String text) {
		return message -> message.subject().contains(text);
	}

	/** Extracts the token from a link like {@code https://host/verify-email#token=XYZ}. */
	public static String tokenIn(EmailMessage message) {
		int index = message.body().indexOf("#token=");
		if (index < 0) {
			throw new IllegalStateException("No token link in message");
		}
		String rest = message.body().substring(index + "#token=".length());
		int end = 0;
		while (end < rest.length() && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '-'
				|| rest.charAt(end) == '_')) {
			end++;
		}
		return rest.substring(0, end);
	}

}
