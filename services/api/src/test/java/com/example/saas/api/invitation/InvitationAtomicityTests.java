package com.example.saas.api.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.RecordingEmailSender;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * AC-INV-04, 05 and 21: each change to an invitation is one transaction. A failure after the invitation row was written
 * (here, the audit write that follows it) must undo that write, the membership, and the outbox row, and must not burn
 * the token. Without these tests the single-transaction claim would rest on reading the code.
 */
class InvitationAtomicityTests extends AuthIntegrationTest {

	@MockitoSpyBean
	private AuditLog audit;

	@Autowired
	private InvitationService service;

	@Autowired
	private TransactionTemplate tx;

	@AfterEach
	void healTheSpy() {
		Mockito.reset(audit);
	}

	private record Company(UUID id, AuthenticatedUser owner) {
	}

	private Company company() {
		return tx.execute(s -> {
			UUID owner = user();
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Atomic Co', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, owner);
			Instant now = Instant.now();
			return new Company(id, new AuthenticatedUser(owner, now, now.plus(Duration.ofHours(12))));
		});
	}

	private UUID user() {
		String email = "member." + UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "created_at, updated_at) VALUES (?, ?, 'M', 'hash', now(), now()) RETURNING id", UUID.class, email, email);
	}

	private void failTheAuditOf(String eventType) {
		// The audit bean is behind a MANDATORY transactional proxy, which also guards the stubbing call itself.
		tx.executeWithoutResult(status -> Mockito.doThrow(new IllegalStateException("audit store failed"))
			.when(audit)
			.recordChange(eq(eventType), any(), any(), any(), any(), any(), any(), any(), any(), any()));
	}

	private String row(UUID invitationId) {
		return jdbc.queryForObject("SELECT status || '|' || coalesce(token_hash, 'none') || '|' || expires_at || '|' || "
				+ "coalesce(accepted_by::text, '-') FROM invitation WHERE id = ?", String.class, invitationId);
	}

	private int queuedEmails(UUID invitationId) {
		return count("SELECT count(*) FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'invitationId' = ?", invitationId.toString());
	}

	@Test
	void aFailureAfterTheResendUpdateLeavesTheOldTokenExpiryAndOutboxUntouched() {
		Company company = company();
		String email = AuthApi.uniqueEmail();
		UUID id = service.create(company.owner(), company.id(), email, Role.VIEWER).id();
		drainOutbox();
		String before = row(id);
		int queued = queuedEmails(id);

		failTheAuditOf("invitation.resent");
		assertThatThrownBy(() -> service.resend(company.owner(), company.id(), id)).isInstanceOf(RuntimeException.class);

		assertThat(row(id)).as("token hash, status and expiry exactly as before").isEqualTo(before);
		assertThat(queuedEmails(id)).as("no second email was queued").isEqualTo(queued);

		Mockito.reset(audit);
		service.resend(company.owner(), company.id(), id);
		assertThat(queuedEmails(id)).isEqualTo(queued + 1);
	}

	@Test
	void aFailureAfterTheRevokeUpdateLeavesTheInvitationPendingAndUsable() {
		Company company = company();
		UUID id = service.create(company.owner(), company.id(), AuthApi.uniqueEmail(), Role.VIEWER).id();
		drainOutbox();
		String before = row(id);

		failTheAuditOf("invitation.revoked");
		assertThatThrownBy(() -> service.revoke(company.owner(), company.id(), id)).isInstanceOf(RuntimeException.class);

		assertThat(row(id)).isEqualTo(before);
		assertThat(before).startsWith("PENDING|");
		assertThat(before).doesNotStartWith("PENDING|none");
	}

	@Test
	void aFailureAfterTheMembershipAndInvitationWritesUndoesBothAndDoesNotBurnTheToken() throws Exception {
		Company company = company();
		String email = AuthApi.uniqueEmail();
		UUID id = service.create(company.owner(), company.id(), email, Role.AGENT).id();
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		mail.clear();
		UUID inviteeId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		String ip = freshIp();
		String csrf = api.sessionCsrf(cookie, ip);
		String before = row(id);

		failTheAuditOf("membership.created");
		int status = api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", token), cookie, csrf, ip)
			.getResponse()
			.getStatus();

		assertThat(status).isEqualTo(500);
		assertThat(row(id)).as("still pending with the same token hash").isEqualTo(before);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", company.id(), inviteeId))
			.as("the membership insert was rolled back").isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type = 'invitation.accepted'",
				company.id())).as("and so was the earlier audit row").isZero();

		Mockito.reset(audit);
		assertThat(api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", token), cookie, csrf, freshIp())
			.getResponse()
			.getStatus()).as("the same token still works once the failure is gone").isEqualTo(200);
	}

}
