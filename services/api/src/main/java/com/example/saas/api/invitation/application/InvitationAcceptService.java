package com.example.saas.api.invitation.application;

import java.time.Clock;
import java.util.UUID;

import com.example.saas.api.auth.application.TokenInvalidException;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;

import static com.example.saas.api.auth.application.AuthRateLimits.requireWithin;

/**
 * Accepting an invitation (SPEC-INV). Counts only failed attempts per client address (D-152, AC-INV-11): the check reads
 * the count without adding to it, so a company onboarding many people from one office address is not blocked by its
 * successes, and a failure adds one. The 11th failed attempt in an hour is refused before any work is done. The read and
 * the write are not one atomic step, so a burst of simultaneous failures can slightly exceed ten; that is acceptable for a
 * guessing limit on a 256-bit token. A rejection that is not about the token (already a member, no room) is not a failure
 * of the token and is not counted.
 */
@Service
public class InvitationAcceptService {

	/** Longer than any token we mint (43 characters); anything beyond is not a token and is not even hashed. */
	static final int MAX_TOKEN_LENGTH = 128;

	private final InvitationAcceptTransactions transactions;

	private final RateLimiter rateLimiter;

	private final Clock clock;

	InvitationAcceptService(InvitationAcceptTransactions transactions, RateLimiter rateLimiter, Clock clock) {
		this.transactions = transactions;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	/**
	 * @param userId the signed-in user, from the session
	 * @param clientKey the rate-limit key of the client address
	 * @throws ValidationFailedException the body has no token (400)
	 * @throws com.example.saas.api.ratelimit.RateLimitedException too many failed attempts from this address (429)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 * @throws TokenInvalidException the invitation cannot be used by this user, for any reason (422)
	 * @throws InvitationConflictException the address matched but the user is already a member or there is no room (409)
	 */
	public AcceptedInvitation accept(UUID userId, String token, String clientKey) {
		if (token == null || token.isBlank()) {
			throw new ValidationFailedException(java.util.List.of(new FieldProblem("token", "REQUIRED")));
		}
		requireWithin(rateLimiter.current(InvitationRateLimits.FAILED_ACCEPT_PER_IP, clientKey), clock);
		try {
			if (token.length() > MAX_TOKEN_LENGTH) {
				throw new TokenInvalidException();
			}
			return transactions.accept(userId, token);
		}
		catch (TokenInvalidException failure) {
			rateLimiter.hit(InvitationRateLimits.FAILED_ACCEPT_PER_IP, clientKey);
			throw failure;
		}
	}

}
