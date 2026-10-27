package com.example.saas.api.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.RecordingEmailSender;
import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.invitation.application.InvitationEmails;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.invitation.application.InvitationView;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Delivery of the invitation email through the outbox: the token is minted here and only its hash is stored (D-146). */
class InvitationDeliveryTests extends AuthIntegrationTest {

	@Autowired
	private InvitationService service;

	@Autowired
	private InvitationEmails emails;

	@Autowired
	private TransactionTemplate tx;

	private record Seed(UUID companyId, AuthenticatedUser owner, String ownerName, String ownerEmail) {
	}

	private Seed company(String name) {
		return tx.execute(s -> {
			String email = "owner." + UUID.randomUUID() + "@example.com";
			UUID owner = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
					+ "password_hash, created_at, updated_at) VALUES (?, ?, 'Olivia Owner', 'hash', now(), now()) "
					+ "RETURNING id", UUID.class, email, email);
			UUID id = jdbc.queryForObject("WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES (?, now(), now()) "
					+ "RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, "
					+ "trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id "
					+ "FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', "
					+ "now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c", UUID.class, name);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, owner);
			Instant now = Instant.now();
			return new Seed(id, new AuthenticatedUser(owner, now, now.plus(Duration.ofHours(12))), "Olivia Owner", email);
		});
	}

	private static String guest() {
		return "guest." + UUID.randomUUID() + "@example.com";
	}

	private String hashOf(UUID invitationId) {
		return jdbc.queryForObject("SELECT token_hash FROM invitation WHERE id = ?", String.class, invitationId);
	}

	@Test
	void deliveryMintsATokenStoresOnlyItsHashAndSendsAFragmentLink() {
		Seed seed = company("Acme Labs");
		String email = guest();
		InvitationView invitation = service.create(seed.owner(), seed.companyId(), email, Role.AGENT);

		drainOutbox();

		List<EmailMessage> sent = mail.sentTo(email);
		assertThat(sent).hasSize(1);
		EmailMessage message = sent.get(0);
		String token = RecordingEmailSender.tokenIn(message);
		assertThat(token).hasSize(TokenCodec.TOKEN_LENGTH);
		assertThat(message.body()).contains("https://app.test.example/invitations/accept#token=" + token);
		assertThat(message.body()).doesNotContain("?token", "/accept/" + token);
		assertThat(hashOf(invitation.id())).as("only the hash is stored").isEqualTo(TokenCodec.hash(token));
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", token)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitation.id()))
			.isEqualTo("PENDING");
		assertThat(service.listPending(seed.companyId())).singleElement().satisfies(v -> assertThat(v.emailSent()).isTrue());
	}

	@Test
	void theEmailNamesTheCompanyAndTheRoleButNotTheInviter() {
		Seed seed = company("Acme Labs");
		String email = guest();
		service.create(seed.owner(), seed.companyId(), email, Role.VIEWER);

		drainOutbox();

		EmailMessage message = mail.sentTo(email).get(0);
		assertThat(message.subject()).as("fixed: the company name is chosen by whoever created the company (D-165)")
			.isEqualTo("You have been invited to a workspace")
			.doesNotContain("Acme");
		assertThat(message.body()).contains("\"Acme Labs\"", "as viewer", "before ", "(UTC)", "we do not vouch for it");
		assertThat(message.body() + message.subject()).as("no inviter name or address (D-155)")
			.doesNotContain(seed.ownerName(), seed.ownerEmail(), "Olivia");
	}

	@Test
	void theOutboxEventNeverHoldsTheAddressOrTheTokenAndIsScrubbedOnceSent() {
		Seed seed = company("Acme Labs");
		String email = guest();
		InvitationView invitation = service.create(seed.owner(), seed.companyId(), email, Role.AGENT);
		String before = jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'invitationId' = ?", String.class, invitation.id().toString());
		UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'invitationId' = ?", UUID.class, invitation.id().toString());

		drainOutbox();

		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		assertThat(before).as("identifiers only").doesNotContain(email, token).contains(invitation.id().toString(),
				seed.companyId().toString());
		assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE id = ?", String.class, eventId))
			.as("a sent event is scrubbed (D-100)").isEqualTo("{}");
		assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, eventId))
			.isEqualTo("SENT");
	}

	@Test
	void aResendDeliversAFreshTokenAndTheEarlierOneStopsWorking() {
		Seed seed = company("Acme Labs");
		String email = guest();
		InvitationView invitation = service.create(seed.owner(), seed.companyId(), email, Role.AGENT);
		drainOutbox();
		String first = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		mail.clear();

		service.resend(seed.owner(), seed.companyId(), invitation.id());
		assertThat(hashOf(invitation.id())).as("revoked at the moment of the resend, before any delivery").isNull();
		drainOutbox();

		String second = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		assertThat(second).isNotEqualTo(first);
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", TokenCodec.hash(first))).isZero();
		assertThat(hashOf(invitation.id())).isEqualTo(TokenCodec.hash(second));
	}

	@Test
	void aRetriedDeliveryMintsANewTokenAndKillsThePreviousOne() {
		Seed seed = company("Acme Labs");
		String email = guest();
		InvitationView invitation = service.create(seed.owner(), seed.companyId(), email, Role.AGENT);

		String first = RecordingEmailSender.tokenIn(emails.prepare(seed.companyId(), invitation.id()).orElseThrow());
		String second = RecordingEmailSender.tokenIn(emails.prepare(seed.companyId(), invitation.id()).orElseThrow());

		assertThat(second).isNotEqualTo(first);
		assertThat(hashOf(invitation.id())).isEqualTo(TokenCodec.hash(second));
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", TokenCodec.hash(first))).isZero();
	}

	@Test
	void anInvitationRevokedBeforeDeliveryIsNeverEmailed() {
		Seed seed = company("Acme Labs");
		String email = guest();
		InvitationView invitation = service.create(seed.owner(), seed.companyId(), email, Role.AGENT);
		UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'invitationId' = ?", UUID.class, invitation.id().toString());
		service.revoke(seed.owner(), seed.companyId(), invitation.id());

		drainOutbox();

		assertThat(mail.sentTo(email)).isEmpty();
		assertThat(hashOf(invitation.id())).isNull();
		assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, eventId))
			.as("the event was consumed, not left to retry or to fail").isEqualTo("SENT");
	}

	@Test
	void anInvitationThatExpiredOrWhoseCompanyIsPendingDeletionIsNeverEmailed() {
		Seed seed = company("Acme Labs");
		String late = guest();
		String deleting = guest();
		InvitationView expired = service.create(seed.owner(), seed.companyId(), late, Role.AGENT);
		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() - interval '1 day' WHERE id = ?", expired.id());
		Seed other = company("Closing Co");
		service.create(other.owner(), other.companyId(), deleting, Role.AGENT);
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", other.companyId());

		drainOutbox();

		assertThat(mail.sentTo(late)).isEmpty();
		assertThat(mail.sentTo(deleting)).isEmpty();
		assertThat(hashOf(expired.id())).isNull();
	}

}
