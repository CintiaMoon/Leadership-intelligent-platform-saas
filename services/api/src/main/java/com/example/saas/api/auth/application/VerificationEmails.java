package com.example.saas.api.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.email.AuthEmails;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.auth.persistence.VerificationTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Mints a verification token for an unverified account and builds the email that carries it. */
@Service
public class VerificationEmails {

	public static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

	private final UserAccountRepository users;

	private final VerificationTokens tokens;

	private final AuthEmails emails;

	private final TransactionTemplate transaction;

	private final Clock clock;

	VerificationEmails(UserAccountRepository users, VerificationTokens tokens, AuthEmails emails,
			TransactionTemplate transaction, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.emails = emails;
		this.transaction = transaction;
		this.clock = clock;
	}

	/**
	 * Creates a fresh token (earlier ones stay valid up to the cap, D-98) and returns the email, or empty if the account is
	 * missing, already verified, or DISABLED. The raw token exists only in the returned message.
	 */
	public Optional<EmailMessage> prepare(UUID userId) {
		return transaction.execute(status -> users.findById(userId)
			.filter(user -> !user.isEmailVerified() && user.getStatus() == UserAccount.Status.ACTIVE)
			.map(user -> {
				String token = TokenCodec.newToken();
				tokens.issue(user.getId(), VerificationTokens.Purpose.EMAIL_VERIFICATION, TokenCodec.hash(token),
						clock.instant(), TOKEN_LIFETIME);
				return emails.verification(user.getEmailOriginal(), token);
			}));
	}

}
