package com.example.saas.api.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.outbox.AuthOutboxHandlers;
import com.example.saas.api.auth.outbox.OutboxRepository;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.auth.persistence.VerificationTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of a password reset: consume the token, store the new hash, invalidate the user's other
 * reset tokens, end every session, and queue the notification email, all or nothing (AC-AUTH-31, 32). The slow
 * password hashing happens before this runs, so the transaction stays short.
 */
@Service
class PasswordResetCommitter {

	private final VerificationTokens tokens;

	private final UserAccountRepository users;

	private final SessionRevoker sessionRevoker;

	private final OutboxRepository outbox;

	private final AuditLog audit;

	private final Clock clock;

	PasswordResetCommitter(VerificationTokens tokens, UserAccountRepository users, SessionRevoker sessionRevoker,
			OutboxRepository outbox, AuditLog audit, Clock clock) {
		this.tokens = tokens;
		this.users = users;
		this.sessionRevoker = sessionRevoker;
		this.outbox = outbox;
		this.audit = audit;
		this.clock = clock;
	}

	/** @throws TokenInvalidException the token is not usable, or the account no longer qualifies; nothing changes */
	@Transactional
	void apply(String tokenHash, String newPasswordHash) {
		Instant now = clock.instant();
		UUID userId = tokens.consume(tokenHash, VerificationTokens.Purpose.PASSWORD_RESET, now)
			.orElseThrow(TokenInvalidException::new);
		UserAccount user = users.findById(userId)
			.filter(account -> account.getStatus() == UserAccount.Status.ACTIVE && account.isEmailVerified())
			.orElseThrow(TokenInvalidException::new);

		user.changePasswordHash(newPasswordHash, now);
		tokens.invalidateOutstanding(userId, VerificationTokens.Purpose.PASSWORD_RESET, now);
		outbox.enqueue(AuthOutboxHandlers.PASSWORD_CHANGED_NOTICE, Map.of("userId", userId.toString()),
				"password-changed-" + UUID.randomUUID(), now);
		audit.success("auth.password.reset_completed", AuditLog.Actor.user, userId, "user", userId);
		// Last, so a failure above never leaves the old password in force with the sessions already gone.
		if (sessionRevoker.revokeAll(userId) > 0) {
			audit.success("auth.session.revoked", AuditLog.Actor.user, userId, "user", userId);
		}
	}

}
