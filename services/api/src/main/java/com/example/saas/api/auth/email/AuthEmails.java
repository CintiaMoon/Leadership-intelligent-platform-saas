package com.example.saas.api.auth.email;

import org.springframework.stereotype.Component;

/**
 * Text of the authentication emails. Links carry the token in the URL fragment, which browsers never send
 * to a server, so it does not appear in access logs (D-44, AC-AUTH-35). Messages say nothing a stranger could
 * use to confirm that an address has an account, because only the address owner receives them.
 */
@Component
public class AuthEmails {

	private final AppProperties properties;

	AuthEmails(AppProperties properties) {
		this.properties = properties;
	}

	/**
	 * Goes to an address nobody has proven they own, so it carries no text chosen by whoever typed that address
	 * into the signup form (D-97): no display name.
	 */
	public EmailMessage verification(String to, String token) {
		return new EmailMessage(to, "Verify your email address", """
				Hello,

				Someone used this address to create an account. Confirm your email address by opening this link within 24 hours:

				%s/verify-email#token=%s

				If you did not create an account, you can ignore this message.
				""".formatted(properties.publicBaseUrl(), token));
	}

	public EmailMessage passwordReset(String to, String displayName, String token) {
		return new EmailMessage(to, "Reset your password", """
				Hello %s,

				Someone asked to reset the password for this account. To choose a new password, open this link within 1 hour:

				%s/reset-password#token=%s

				If you did not ask for this, you can ignore this message. Your password has not changed.
				""".formatted(displayName, properties.publicBaseUrl(), token));
	}

	public EmailMessage passwordChanged(String to, String displayName) {
		return new EmailMessage(to, "Your password was changed", """
				Hello %s,

				The password for your account was just changed, and every device was signed out.

				If this was you, no action is needed. If it was not, reset your password at %s/forgot-password right away.
				""".formatted(displayName, properties.publicBaseUrl()));
	}

	public EmailMessage accountAlreadyExists(String to, String displayName) {
		return new EmailMessage(to, "Someone tried to register with your email address", """
				Hello %s,

				Someone tried to create an account with this email address, but an account already exists.

				If it was you, log in at %s/login. If you forgot your password, you can reset it at %s/forgot-password.

				If it was not you, you can ignore this message. Your account is unchanged.
				""".formatted(displayName, properties.publicBaseUrl(), properties.publicBaseUrl()));
	}

}
