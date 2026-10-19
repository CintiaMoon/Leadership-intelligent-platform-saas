package com.example.saas.api.auth.persistence;

import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.example.saas.api.common.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the audit trail (docs/architecture/audit-events.md). Callers pass identifiers and a reason
 * code only: no passwords, tokens, cookies, or free text. Runs inside the caller's transaction so the
 * record commits or rolls back with the change it describes.
 */
@Repository
public class AuditLog {

	public enum Actor {

		user, operator, system

	}

	private final JdbcClient jdbc;

	private final Clock clock;

	AuditLog(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void success(String eventType, Actor actorType, UUID actorId, String targetType, UUID targetId) {
		record(eventType, actorType, actorId, targetType, targetId, "success", null);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(String eventType, Actor actorType, UUID actorId, String targetType, UUID targetId,
			String outcome, String reasonCode) {
		jdbc.sql("""
				INSERT INTO audit_event (occurred_at, event_type, actor_type, actor_id, target_type, target_id,
				                         outcome, correlation_id, reason_code)
				VALUES (:at, :type, :actorType, :actorId, :targetType, :targetId, :outcome, :correlation, :reason)
				""")
			.param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
			.param("type", eventType)
			.param("actorType", actorType.name())
			.param("actorId", actorId, Types.OTHER)
			.param("targetType", targetType)
			.param("targetId", targetId, Types.OTHER)
			.param("outcome", outcome)
			.param("correlation", MDC.get(CorrelationIdFilter.MDC_KEY), Types.VARCHAR)
			.param("reason", reasonCode, Types.VARCHAR)
			.update();
	}

}
