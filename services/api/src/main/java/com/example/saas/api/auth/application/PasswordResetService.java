package com.example.saas.api.auth.application;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import com.example.saas.api.auth.domain.PasswordHasher;
import com.example.saas.api.auth.domain.PasswordPolicy;
import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.persistence.VerificationTokens;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import org.springframework.stereotype.Service;

/**
 * Completes a password reset with the emailed token (AC-AUTH-31, 32, 33, 35, 37).
 *
 * <p>Order matters. An unusable token is refused first and cheaply, with the one generic answer, so someone
 * without a valid token learns nothing about the password rules and cannot make the server do hashing work. Only
 * then is the new password checked; a weak password is a validation error and leaves the token unused so the user
 * can try again. The slow hashing happens before the short transaction that consumes the token.
 */
@Service
public class PasswordResetService {

	private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{" + TokenCodec.TOKEN_LENGTH + "}$");

	private final VerificationTokens tokens;

	private final PasswordPolicy policy;

	private final PasswordHasher hasher;

	private final PasswordResetCommitter committer;

	private final Clock clock;

	PasswordResetService(VerificationTokens tokens, PasswordPolicy policy, PasswordHasher hasher,
			PasswordResetCommitter committer, Clock clock) {
		this.tokens = tokens;
		this.policy = policy;
		this.hasher = hasher;
		this.committer = committer;
		this.clock = clock;
	}

	/**
	 * @throws TokenInvalidException the token is malformed, unknown, expired, or already used (422)
	 * @throws ValidationFailedException the new password breaks the password policy (400)
	 * @throws com.example.saas.api.auth.domain.PasswordHashingBusyException no hashing capacity (503)
	 */
	public void confirm(String token, String newPassword) {
		if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
			throw new TokenInvalidException();
		}
		String tokenHash = TokenCodec.hash(token);
		if (!tokens.isUsable(tokenHash, VerificationTokens.Purpose.PASSWORD_RESET, clock.instant())) {
			throw new TokenInvalidException();
		}
		policy.check(newPassword)
			.ifPresent(violation -> {
				throw new ValidationFailedException(List.of(new FieldProblem("newPassword", violation.name())));
			});
		String newHash = hasher.hash(PasswordPolicy.normalize(newPassword));
		committer.apply(tokenHash, newHash);
	}

}
