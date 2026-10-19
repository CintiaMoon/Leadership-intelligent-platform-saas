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
 * Requests a new verification email by address (AC-AUTH-18, 20). Every accepted request writes the same
 * outbox record, whether or not an unverified account exists; the lookup runs later in the worker, so the
 * response and its timing never reveal whether an account exists.
 */
@Service
public class ResendVerificationService {

	private final RateLimiter rateLimiter;

	private final OutboxRepository outbox;

	private final Clock clock;

	ResendVerificationService(RateLimiter rateLimiter, OutboxRepository outbox, Clock clock) {
		this.rateLimiter = rateLimiter;
		this.outbox = outbox;
		this.clock = clock;
	}

	@Transactional
	public void resend(String email, String clientKey) {
		AuthRateLimits.requireWithin(rateLimiter.hit(AuthRateLimits.RESEND_IP, clientKey), clock);
		EmailAddress address = EmailAddress.parse(email)
			.orElseThrow(() -> new ValidationFailedException(List.of(new FieldProblem("email", "INVALID_EMAIL"))));
		// The per-account limit answers the same neutral response when exceeded; only nothing is queued.
		if (rateLimiter.hit(AuthRateLimits.RESEND_ACCOUNT, address.normalized()).allowed()) {
			outbox.enqueue(AuthOutboxHandlers.RESEND_VERIFICATION, Map.of("email", address.normalized()),
					"resend-" + UUID.randomUUID(), clock.instant());
		}
	}

}
