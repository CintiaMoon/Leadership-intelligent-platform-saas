package com.example.saas.api.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.auth.persistence.VerificationTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consumes an email-verification token (AC-AUTH-03, 12, 37). */
@Service
public class VerifyEmailService {

	private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{" + TokenCodec.TOKEN_LENGTH + "}$");

	private final VerificationTokens tokens;

	private final UserAccountRepository users;

	private final AuditLog audit;

	private final Clock clock;

	VerifyEmailService(VerificationTokens tokens, UserAccountRepository users, AuditLog audit, Clock clock) {
		this.tokens = tokens;
		this.users = users;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Marks the account's email verified.
	 *
	 * @throws TokenInvalidException the token is malformed, unknown, expired, or already consumed. Concurrent
	 *         attempts with one token produce exactly one success.
	 */
	@Transactional
	public void verify(String token) {
		if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
			throw new TokenInvalidException();
		}
		Instant now = clock.instant();
		UUID userId = tokens.consume(TokenCodec.hash(token), VerificationTokens.Purpose.EMAIL_VERIFICATION, now)
			.orElseThrow(TokenInvalidException::new);
		users.findById(userId).orElseThrow(TokenInvalidException::new).markEmailVerified(now);
		tokens.invalidateOutstanding(userId, VerificationTokens.Purpose.EMAIL_VERIFICATION, now);
		audit.success("auth.email.verified", AuditLog.Actor.user, userId, "user", userId);
	}

}
