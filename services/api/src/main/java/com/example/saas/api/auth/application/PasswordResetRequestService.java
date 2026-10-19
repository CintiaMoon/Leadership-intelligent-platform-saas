package com.example.saas.api.auth.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.domain.EmailAddress;
import com.example.saas.api.auth.outbox.AuthOutboxHandlers;
import com.example.saas.api.auth.outbox.OutboxRepository;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks for a password-reset email by address (AC-AUTH-30, 34). Every accepted request queues the same outbox
 * record whether or not an account exists, is verified, or is disabled; the lookup runs later in the worker, so
 * neither the response nor its timing reveals anything about the account.
 */
@Service
public class PasswordResetRequestService {

	private final RateLimiter rateLimiter;

	private final OutboxRepository outbox;

	private final Clock clock;

	PasswordResetRequestService(RateLimiter rateLimiter, OutboxRepository outbox, Clock clock) {
		this.rateLimiter = rateLimiter;
		this.outbox = outbox;
		this.clock = clock;
	}

	/**
	 * @throws ValidationFailedException the address is malformed (400)
	 * @throws com.example.saas.api.ratelimit.RateLimitedException the address exceeded 10 requests an hour (429)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 */
	@Transactional
	public void request(String email, String clientKey) {
		AuthRateLimits.requireWithin(rateLimiter.hit(AuthRateLimits.RESET_IP, clientKey), clock);
		EmailAddress address = EmailAddress.parse(email)
			.orElseThrow(() -> new ValidationFailedException(List.of(new FieldProblem("email", "INVALID_EMAIL"))));
		// More than 3 an hour for one account: still the neutral answer, but nothing is queued.
		if (rateLimiter.hit(AuthRateLimits.RESET_ACCOUNT, address.normalized()).allowed()) {
			outbox.enqueue(AuthOutboxHandlers.PASSWORD_RESET_REQUEST, Map.of("email", address.normalized()),
					"reset-" + UUID.randomUUID(), clock.instant());
		}
	}

}
