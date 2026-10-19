package com.example.saas.api.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.auth.persistence.VerificationTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Mints a password-reset token for an eligible account and builds the email that carries it (AC-AUTH-30, 31). */
@Service
public class PasswordResetEmails {

	/** Reset links stop working after one hour (AC-AUTH-31). */
	public static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

	private final UserAccountRepository users;

	private final VerificationTokens tokens;

	private final AuthEmails emails;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final Clock clock;

	PasswordResetEmails(UserAccountRepository users, VerificationTokens tokens, AuthEmails emails, AuditLog audit,
			TransactionTemplate transaction, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.emails = emails;
		this.audit = audit;
		this.transaction = transaction;
		this.clock = clock;
	}

	/**
	 * Creates a fresh token (invalidating earlier reset tokens) and returns the email, or empty if the account is
	 * missing, DISABLED, or has no verified email. The raw token exists only in the returned message.
	 */
	public Optional<EmailMessage> prepare(UUID userId) {
		return transaction.execute(status -> users.findById(userId)
			.filter(user -> user.getStatus() == UserAccount.Status.ACTIVE && user.isEmailVerified())
			.map(user -> {
				String token = TokenCodec.newToken();
				tokens.issue(user.getId(), VerificationTokens.Purpose.PASSWORD_RESET, TokenCodec.hash(token),
						clock.instant(), TOKEN_LIFETIME);
				audit.success("auth.password.reset_requested", AuditLog.Actor.user, user.getId(), "user", user.getId());
				return emails.passwordReset(user.getEmailOriginal(), user.getDisplayName(), token);
			}));
	}

}
