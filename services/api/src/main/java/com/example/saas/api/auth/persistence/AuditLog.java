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
		recordForCompany(eventType, actorType, actorId, targetType, targetId, null, outcome, reasonCode);
	}

	/** Like {@link #record} for an event that belongs to one company (null for global events). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordForCompany(String eventType, Actor actorType, UUID actorId, String targetType, UUID targetId,
			UUID companyId, String outcome, String reasonCode) {
		recordChange(eventType, actorType, actorId, targetType, targetId, companyId, outcome, reasonCode, null, null);
	}

	/**
	 * Like {@link #recordForCompany} for a change of role or status. The values are enum names only; the database
	 * accepts nothing else (D-130).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordChange(String eventType, Actor actorType, UUID actorId, String targetType, UUID targetId,
			UUID companyId, String outcome, String reasonCode, String before, String after) {
		jdbc.sql("""
				INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, actor_id, target_type,
				                         target_id, outcome, correlation_id, reason_code, before_value, after_value)
				VALUES (:at, :type, :company, :actorType, :actorId, :targetType, :targetId, :outcome, :correlation,
				        :reason, :before, :after)
				""")
			.param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
			.param("type", eventType)
			.param("company", companyId, Types.OTHER)
			.param("actorType", actorType.name())
			.param("actorId", actorId, Types.OTHER)
			.param("targetType", targetType)
			.param("targetId", targetId, Types.OTHER)
			.param("outcome", outcome)
			.param("correlation", MDC.get(CorrelationIdFilter.MDC_KEY), Types.VARCHAR)
			.param("reason", reasonCode, Types.VARCHAR)
			.param("before", before, Types.VARCHAR)
			.param("after", after, Types.VARCHAR)
			.update();
	}

}
