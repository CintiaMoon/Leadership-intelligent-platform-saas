package com.example.saas.api.auth.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox events (ADR-003). Each cycle claims a batch, handles and sends each event outside any
 * database transaction, and records the outcome. A crash after the claim only delays delivery: the lease
 * expires and another worker takes the row. Failures are retried with exponential backoff and then marked
 * FAILED so an operator can see them. Log lines carry event IDs and error classes only.
 */
@Component
public class OutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

	static final int BATCH_SIZE = 10;

	static final Duration LEASE = Duration.ofMinutes(5);

	static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(30);

	static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

	private final OutboxRepository repository;

	private final EmailSender emailSender;

	private final Clock clock;

	private final Map<String, OutboxHandler> handlers = new HashMap<>();

	OutboxWorker(OutboxRepository repository, EmailSender emailSender, Clock clock, List<OutboxHandler> handlers) {
		this.repository = repository;
		this.emailSender = emailSender;
		this.clock = clock;
		handlers.forEach(handler -> this.handlers.put(handler.eventType(), handler));
	}

	/** Runs one claim-and-deliver cycle and returns how many events it processed. */
	public int runOnce() {
		List<OutboxEvent> batch = repository.claim(clock.instant(), BATCH_SIZE, LEASE);
		for (OutboxEvent event : batch) {
			process(event);
		}
		return batch.size();
	}

	private void process(OutboxEvent event) {
		try {
			OutboxHandler handler = handlers.get(event.eventType());
			if (handler == null) {
				throw new IllegalStateException("No handler for event type");
			}
			Optional<EmailMessage> message = handler.handle(event);
			message.ifPresent(emailSender::send);
			repository.markSent(event.id(), clock.instant());
		}
		catch (Exception ex) {
			Instant now = clock.instant();
			repository.markFailed(event, ex.getClass().getSimpleName(), now, retryDelay(event.attempts()));
			log.warn("Outbox event {} failed on attempt {} of {}: {}", event.id(), event.attempts(),
					event.maxAttempts(), ex.getClass().getSimpleName());
		}
	}

	static Duration retryDelay(int attempts) {
		long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
		Duration delay = BASE_RETRY_DELAY.multipliedBy(multiplier);
		return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
	}

}
