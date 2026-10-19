package com.example.saas.api.auth.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.AuthTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-003: claiming, retries, crash recovery, and concurrent workers against PostgreSQL. */
class OutboxWorkerTests extends AuthIntegrationTest {

	@Autowired
	OutboxRepository outbox;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	Clock clock;

	private UUID enqueue(String type, Map<String, String> payload) {
		String key = "test-" + UUID.randomUUID();
		transaction.executeWithoutResult(status -> outbox.enqueue(type, payload, key, clock.instant()));
		return jdbc.queryForObject("SELECT id FROM outbox_event WHERE idempotency_key = ?", UUID.class, key);
	}

	private String status(UUID id) {
		return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id);
	}

	private void makeDue(UUID id) {
		jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
	}

	@Test
	void anEventIsDeliveredOnceAndMarkedSent() {
		String to = UUID.randomUUID() + "@example.com";
		UUID id = enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", to));

		assertThat(worker.runOnce()).isEqualTo(1);

		assertThat(mail.sentTo(to)).hasSize(1);
		assertThat(status(id)).isEqualTo("SENT");
		assertThat(worker.runOnce()).as("nothing left to do").isZero();
		assertThat(mail.sentTo(to)).hasSize(1);
	}

	@Test
	void concurrentWorkersNeverDeliverTheSameEventTwice() throws Exception {
		int events = 60;
		Set<String> recipients = new HashSet<>();
		for (int i = 0; i < events; i++) {
			String to = UUID.randomUUID() + "@example.com";
			recipients.add(to);
			enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", to));
		}
		int workers = 4;
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < workers; i++) {
				futures.add(executor.submit(() -> {
					go.await();
					int idle = 0;
					while (idle < 3) {
						idle = worker.runOnce() == 0 ? idle + 1 : 0;
					}
					return null;
				}));
			}
			go.countDown();
			for (Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		List<String> delivered = mail.sent().stream().map(m -> m.to()).filter(recipients::contains).toList();
		assertThat(delivered).as("each event delivered exactly once").hasSize(events).doesNotHaveDuplicates();
	}

	@Test
	void aFailedDeliveryIsRetriedLaterAndEventuallyMarkedFailed() {
		UUID id = enqueue(AuthTestConfiguration.TEST_FAIL, Map.of());
		jdbc.update("UPDATE outbox_event SET max_attempts = 2 WHERE id = ?", id);

		assertThat(worker.runOnce()).isEqualTo(1);
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE id = ?", String.class, id))
			.isEqualTo("IllegalStateException");
		assertThat(worker.runOnce()).as("backoff: not due yet").isZero();

		makeDue(id);
		assertThat(worker.runOnce()).isEqualTo(1);

		assertThat(status(id)).as("attempts used up").isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM outbox_event WHERE id = ?", Boolean.class,
				id)).isTrue();
		makeDue(id);
		assertThat(worker.runOnce()).as("failed events are not retried").isZero();
	}

	@Test
	void anEmailFailureDoesNotLoseTheEventAndTheLaterRetryDelivers() {
		String to = UUID.randomUUID() + "@example.com";
		UUID id = enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", to));
		mail.failWith(new IllegalStateException("smtp down"));

		worker.runOnce();
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(mail.sentTo(to)).isEmpty();

		mail.failWith(null);
		makeDue(id);
		worker.runOnce();

		assertThat(status(id)).isEqualTo("SENT");
		assertThat(mail.sentTo(to)).hasSize(1);
	}

	@Test
	void anEventClaimedByACrashedWorkerIsRecoveredAfterItsLeaseExpires() {
		String to = UUID.randomUUID() + "@example.com";
		UUID id = enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", to));

		// A worker claims the event and then dies before delivering.
		List<OutboxEvent> claimed = outbox.claim(clock.instant(), 100, Duration.ofMinutes(5));
		assertThat(claimed).extracting(OutboxEvent::id).contains(id);
		assertThat(status(id)).isEqualTo("IN_FLIGHT");

		assertThat(worker.runOnce()).as("lease still held").isZero();

		jdbc.update("UPDATE outbox_event SET locked_until = now() - interval '1 second' WHERE id = ?", id);
		worker.runOnce();

		assertThat(status(id)).isEqualTo("SENT");
		assertThat(mail.sentTo(to)).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id))
			.as("the recovery counts as a second attempt")
			.isEqualTo(2);
	}

	@Test
	void aRepeatedIdempotencyKeyQueuesOnlyOneEvent() {
		String key = "same-key-" + UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			transaction.executeWithoutResult(
					status -> outbox.enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", "x@example.com"), key,
							clock.instant()));
		}

		assertThat(count("SELECT count(*) FROM outbox_event WHERE idempotency_key = ?", key)).isEqualTo(1);
	}

	@Test
	void anEventWithoutAHandlerFailsWithoutBlockingOthers() {
		UUID orphan = enqueue("NO_SUCH_TYPE", Map.of());
		String to = UUID.randomUUID() + "@example.com";
		UUID good = enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", to));

		worker.runOnce();

		assertThat(status(good)).isEqualTo("SENT");
		assertThat(status(orphan)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE id = ?", String.class, orphan))
			.isEqualTo("IllegalStateException");
	}

	@Test
	void enqueueOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> outbox.enqueue(AuthTestConfiguration.TEST_OK, Map.of(), "no-tx-" + UUID.randomUUID(),
				clock.instant())).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void enqueueRollsBackWithTheBusinessTransaction() {
		String key = "rollback-" + UUID.randomUUID();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			outbox.enqueue(AuthTestConfiguration.TEST_OK, Map.of("to", "x@example.com"), key, clock.instant());
			throw new IllegalStateException("business failure");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("SELECT count(*) FROM outbox_event WHERE idempotency_key = ?", key)).isZero();
	}

	@Test
	void retryDelayDoublesAndIsCapped() {
		assertThat(OutboxWorker.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
		assertThat(OutboxWorker.retryDelay(2)).isEqualTo(Duration.ofSeconds(60));
		assertThat(OutboxWorker.retryDelay(3)).isEqualTo(Duration.ofSeconds(120));
		assertThat(OutboxWorker.retryDelay(30)).isEqualTo(Duration.ofHours(1));
	}

}
