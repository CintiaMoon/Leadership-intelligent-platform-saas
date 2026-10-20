package com.example.saas.api.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.saas.api.retention.RetentionPurge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-44 (D-100): no address is kept in a delivered outbox event, and old operational rows are deleted. */
class RetentionTests extends AuthIntegrationTest {

	@Autowired
	RetentionPurge purge;

	private void outbox(String key, String status, Instant completedAt) {
		jdbc.update("INSERT INTO outbox_event (event_type, payload, idempotency_key, status, next_attempt_at, created_at, "
				+ "completed_at) VALUES ('t', '{\"email\":\"x@example.com\"}'::jsonb, ?, ?, now(), now(), ?)", key, status,
				completedAt == null ? null : java.sql.Timestamp.from(completedAt));
	}

	private void audit(String type, Instant at) {
		jdbc.update("INSERT INTO audit_event (occurred_at, event_type, actor_type, target_type, outcome) "
				+ "VALUES (?, ?, 'user', 'user', 'denied')", java.sql.Timestamp.from(at), type);
	}

	@Test
	void aDeliveredEventKeepsNoAddress() throws Exception {
		String email = uniqueEmail();
		api.resend(email, freshIp());
		api.requestReset(email, freshIp());
		drainOutbox();

		assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'SENT'")).as("something was delivered")
			.isGreaterThanOrEqualTo(2);
		assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'SENT' AND payload::text LIKE ?",
				"%" + email + "%")).as("the address of an account that does not exist is not kept").isZero();
		assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'SENT' AND payload <> '{}'::jsonb"))
			.isZero();
	}

	@Test
	void rowsPastTheirRetentionAreDeletedAndNothingElseIs() {
		String tag = java.util.UUID.randomUUID().toString();
		Instant now = Instant.now();
		outbox("sent-old-" + tag, "SENT", now.minus(8, ChronoUnit.DAYS));
		outbox("sent-new-" + tag, "SENT", now.minus(6, ChronoUnit.DAYS));
		outbox("failed-old-" + tag, "FAILED", now.minus(31, ChronoUnit.DAYS));
		outbox("failed-new-" + tag, "FAILED", now.minus(29, ChronoUnit.DAYS));
		outbox("pending-old-" + tag, "PENDING", null);
		audit("auth.login.failed", now.minus(366, ChronoUnit.DAYS));
		audit("auth.login.failed", now.minus(364, ChronoUnit.DAYS));
		audit("auth.user.registered", now.minus(900, ChronoUnit.DAYS));

		int removed = purge.purge(now);

		assertThat(removed).isGreaterThanOrEqualTo(3);
		assertThat(count("SELECT count(*) FROM outbox_event WHERE idempotency_key LIKE ?", "%-" + tag)).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM outbox_event WHERE idempotency_key IN (?, ?, ?)", "sent-new-" + tag,
				"failed-new-" + tag, "pending-old-" + tag)).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.login.failed' "
				+ "AND occurred_at < now() - interval '365 days'")).isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.user.registered' "
				+ "AND occurred_at < now() - interval '800 days'")).as("other audit events are kept").isEqualTo(1);
	}

}
