package com.example.saas.api.auth.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL outbox (ADR-003). {@link #enqueue} joins the caller's transaction so the work record commits
 * with the business change. {@link #claim} is one statement using {@code FOR UPDATE SKIP LOCKED}, so
 * concurrent workers on any number of instances never receive the same row, and it holds no transaction
 * open while the work is done.
 */
@Repository
public class OutboxRepository {

	private static final TypeReference<Map<String, String>> PAYLOAD = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JsonMapper json;

	OutboxRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Adds a work record in the caller's transaction. A repeated idempotency key is ignored. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(String eventType, Map<String, String> payload, String idempotencyKey, Instant now) {
		jdbc.sql("""
				INSERT INTO outbox_event (event_type, payload, idempotency_key, next_attempt_at, created_at)
				VALUES (:type, CAST(:payload AS jsonb), :key, :now, :now)
				ON CONFLICT (idempotency_key) DO NOTHING
				""")
			.param("type", eventType)
			.param("payload", json.writeValueAsString(payload))
			.param("key", idempotencyKey)
			.param("now", utc(now))
			.update();
	}

	/**
	 * Claims up to {@code limit} due rows (pending, or in flight with an expired lease after a crash) and
	 * leases them for {@code lease}.
	 */
	public List<OutboxEvent> claim(Instant now, int limit, Duration lease) {
		return jdbc.sql("""
				WITH due AS (
				    SELECT id FROM outbox_event
				    WHERE (status = 'PENDING' AND next_attempt_at <= :now)
				       OR (status = 'IN_FLIGHT' AND locked_until < :now)
				    ORDER BY next_attempt_at
				    LIMIT :limit
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE outbox_event o
				SET status = 'IN_FLIGHT', attempts = o.attempts + 1, locked_until = :leaseEnd
				FROM due
				WHERE o.id = due.id
				RETURNING o.id, o.event_type, o.payload::text AS payload, o.attempts, o.max_attempts
				""")
			.param("now", utc(now))
			.param("limit", limit)
			.param("leaseEnd", utc(now.plus(lease)))
			.query((rs, rowNum) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
					json.readValue(rs.getString("payload"), PAYLOAD), rs.getInt("attempts"), rs.getInt("max_attempts")))
			.list();
	}

	/** Marks the event delivered and scrubs its payload, which can hold an email address (D-100). */
	public void markSent(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE outbox_event SET status = 'SENT', completed_at = :now, locked_until = NULL, last_error = NULL,
				    payload = '{}'::jsonb
				WHERE id = :id AND status = 'IN_FLIGHT'
				""")
			.param("now", utc(now))
			.param("id", id)
			.update();
	}

	/** Schedules a retry, or marks the row FAILED (visible for repair) once its attempts are used up. */
	public void markFailed(OutboxEvent event, String errorCode, Instant now, Duration retryDelay) {
		boolean exhausted = event.attempts() >= event.maxAttempts();
		jdbc.sql("""
				UPDATE outbox_event
				SET status = :status, last_error = :error, locked_until = NULL,
				    next_attempt_at = :next, completed_at = :completed
				WHERE id = :id AND status = 'IN_FLIGHT'
				""")
			.param("status", exhausted ? "FAILED" : "PENDING")
			.param("error", errorCode)
			.param("next", utc(now.plus(retryDelay)))
			.param("completed", exhausted ? utc(now) : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
			.param("id", event.id())
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
