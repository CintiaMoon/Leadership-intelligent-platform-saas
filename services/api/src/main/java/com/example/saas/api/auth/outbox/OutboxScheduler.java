package com.example.saas.api.auth.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Disabled in tests, which call {@link OutboxWorker#runOnce()} directly. */
@Component
@ConditionalOnProperty(name = "app.outbox.worker-enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

	private final OutboxWorker worker;

	OutboxScheduler(OutboxWorker worker) {
		this.worker = worker;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval:2s}")
	void poll() {
		try {
			while (worker.runOnce() == OutboxWorker.BATCH_SIZE) {
				// A full batch means more may be waiting.
			}
		}
		catch (RuntimeException ex) {
			log.warn("Outbox poll failed: {}", ex.getClass().getSimpleName());
		}
	}

}
