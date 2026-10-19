package com.example.saas.api.auth.outbox;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.application.PasswordResetEmails;
import com.example.saas.api.auth.application.VerificationEmails;
import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import org.springframework.stereotype.Component;

/** Event types written by the signup and resend flows. Payloads hold identifiers only. */
public final class AuthOutboxHandlers {

	public static final String VERIFICATION_EMAIL = "AUTH_VERIFICATION_EMAIL";

	public static final String ACCOUNT_EXISTS_NOTICE = "AUTH_ACCOUNT_EXISTS_NOTICE";

	public static final String RESEND_VERIFICATION = "AUTH_RESEND_VERIFICATION";

	public static final String PASSWORD_RESET_REQUEST = "AUTH_PASSWORD_RESET_REQUEST";

	public static final String PASSWORD_CHANGED_NOTICE = "AUTH_PASSWORD_CHANGED_NOTICE";

	private AuthOutboxHandlers() {
	}

	/**
	 * Payload {@code email} (normalized): the lookup happens here, after the request returned, so the request
	 * takes the same time whether or not an eligible account exists (AC-AUTH-30).
	 */
	@Component
	static class PasswordResetRequestHandler implements OutboxHandler {

		private final UserAccountRepository users;

		private final PasswordResetEmails resetEmails;

		PasswordResetRequestHandler(UserAccountRepository users, PasswordResetEmails resetEmails) {
			this.users = users;
			this.resetEmails = resetEmails;
		}

		@Override
		public String eventType() {
			return PASSWORD_RESET_REQUEST;
		}

		@Override
		public Optional<EmailMessage> handle(OutboxEvent event) {
			return users.findByEmailNormalized(event.payload().get("email"))
				.flatMap(user -> resetEmails.prepare(user.getId()));
		}

	}

	/** Payload {@code userId}: tells the owner their password changed. Carries no secret and no link to act on. */
	@Component
	static class PasswordChangedNoticeHandler implements OutboxHandler {

		private final UserAccountRepository users;

		private final AuthEmails emails;

		PasswordChangedNoticeHandler(UserAccountRepository users, AuthEmails emails) {
			this.users = users;
			this.emails = emails;
		}

		@Override
		public String eventType() {
			return PASSWORD_CHANGED_NOTICE;
		}

		@Override
		public Optional<EmailMessage> handle(OutboxEvent event) {
			return users.findById(UUID.fromString(event.payload().get("userId")))
				.map(user -> emails.passwordChanged(user.getEmailOriginal(), user.getDisplayName()));
		}

	}

	/** Payload {@code userId}: send the verification link for a new account. */
	@Component
	static class VerificationEmailHandler implements OutboxHandler {

		private final VerificationEmails verificationEmails;

		VerificationEmailHandler(VerificationEmails verificationEmails) {
			this.verificationEmails = verificationEmails;
		}

		@Override
		public String eventType() {
			return VERIFICATION_EMAIL;
		}

		@Override
		public Optional<EmailMessage> handle(OutboxEvent event) {
			return verificationEmails.prepare(UUID.fromString(event.payload().get("userId")));
		}

	}

	/** Payload {@code userId}: tell the owner that someone tried to register their address. */
	@Component
	static class AccountExistsNoticeHandler implements OutboxHandler {

		private final UserAccountRepository users;

		private final AuthEmails emails;

		AccountExistsNoticeHandler(UserAccountRepository users, AuthEmails emails) {
			this.users = users;
			this.emails = emails;
		}

		@Override
		public String eventType() {
			return ACCOUNT_EXISTS_NOTICE;
		}

		@Override
		public Optional<EmailMessage> handle(OutboxEvent event) {
			return users.findById(UUID.fromString(event.payload().get("userId")))
				.filter(user -> user.getStatus() == UserAccount.Status.ACTIVE)
				.map(user -> emails.accountAlreadyExists(user.getEmailOriginal(), user.getDisplayName()));
		}

	}

	/**
	 * Payload {@code email} (normalized): the lookup happens here, after the request returned, so the
	 * request takes the same time whether or not an unverified account exists (AC-AUTH-20).
	 */
	@Component
	static class ResendVerificationHandler implements OutboxHandler {

		private final UserAccountRepository users;

		private final VerificationEmails verificationEmails;

		ResendVerificationHandler(UserAccountRepository users, VerificationEmails verificationEmails) {
			this.users = users;
			this.verificationEmails = verificationEmails;
		}

		@Override
		public String eventType() {
			return RESEND_VERIFICATION;
		}

		@Override
		public Optional<EmailMessage> handle(OutboxEvent event) {
			return users.findByEmailNormalized(event.payload().get("email"))
				.flatMap(user -> verificationEmails.prepare(user.getId()));
		}

	}

}
