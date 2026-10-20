package com.example.saas.api.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes records past their retention period (D-100) in small batches, so a large backlog never holds a long
 * lock. Only rows whose purpose is over are touched: delivered and abandoned outbox events, and failed-login audit
 * events. Other audit events stay until a retention policy exists.
 */
@Component
public class RetentionPurge {

	private static final Logger log = LoggerFactory.getLogger(RetentionPurge.class);

	static final int BATCH = 5000;

	private final JdbcClient jdbc;

	private final RetentionProperties properties;

	private final Clock clock;

	RetentionPurge(JdbcClient jdbc, RetentionProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.retention.interval:6h}", initialDelayString = "${app.retention.interval:6h}")
	void scheduled() {
		try {
			int removed = purge(clock.instant());
			if (removed > 0) {
				log.info("Retention purge removed {} rows", removed);
			}
		}
		catch (RuntimeException ex) {
			// Retry at the next run; never take the scheduler thread down.
			log.warn("Retention purge failed: {}", ex.getClass().getName());
		}
	}

	/** @return how many rows were deleted */
	public int purge(Instant now) {
		return deleteInBatches("""
				DELETE FROM outbox_event WHERE ctid IN (
				    SELECT ctid FROM outbox_event WHERE status = 'SENT' AND completed_at < :cutoff LIMIT :batch)
				""", now.minus(properties.outboxSent()))
				+ deleteInBatches("""
				DELETE FROM outbox_event WHERE ctid IN (
				    SELECT ctid FROM outbox_event WHERE status = 'FAILED' AND completed_at < :cutoff LIMIT :batch)
				""", now.minus(properties.outboxFailed()))
				+ deleteInBatches("""
				DELETE FROM audit_event WHERE ctid IN (
				    SELECT ctid FROM audit_event
				    WHERE event_type = 'auth.login.failed' AND occurred_at < :cutoff LIMIT :batch)
				""", now.minus(properties.failedLoginAudit()));
	}

	private int deleteInBatches(String sql, Instant cutoff) {
		int total = 0;
		int deleted;
		do {
			deleted = jdbc.sql(sql)
				.param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
				.param("batch", BATCH)
				.update();
			total += deleted;
		}
		while (deleted == BATCH);
		return total;
	}

}
