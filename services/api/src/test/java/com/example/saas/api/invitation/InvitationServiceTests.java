package com.example.saas.api.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.invitation.application.InvitationConflictException;
import com.example.saas.api.invitation.application.InvitationRateLimits;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.invitation.application.InvitationView;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.ratelimit.RateLimitedException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.ReauthenticationRequiredException;
import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Creating, resending, revoking and listing invitations against PostgreSQL (SPEC-INV, Phase 2). */
class InvitationServiceTests extends AuthIntegrationTest {

	@Autowired
	private InvitationService service;

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private RateLimiter limiter;

	private record Person(UUID userId, String email) {

		AuthenticatedUser session() {
			Instant now = Instant.now();
			return new AuthenticatedUser(userId, now, now.plus(Duration.ofHours(12)));
		}

		/** A session whose last login was 11 minutes ago: outside the 10-minute window (AC-INV-27). */
		AuthenticatedUser staleSession() {
			Instant now = Instant.now();
			return new AuthenticatedUser(userId, now.minus(Duration.ofMinutes(11)), now.plus(Duration.ofHours(12)));
		}

	}

	private record Team(UUID companyId, Person owner, Person admin, Person agent, Person viewer) {
	}

	private Person newUser() {
		String email = "member." + UUID.randomUUID() + "@example.com";
		UUID id = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'Member', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
		return new Person(id, email);
	}

	private void seat(UUID companyId, Person person, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, person.userId(), role, status);
	}

	private Team team() {
		return tx.execute(s -> {
			Person owner = newUser();
			Person admin = newUser();
			Person agent = newUser();
			Person viewer = newUser();
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Invite Co', now(), now()) RETURNING id",
					UUID.class);
			seat(id, owner, "OWNER", "ACTIVE");
			seat(id, admin, "ADMIN", "ACTIVE");
			seat(id, agent, "AGENT", "ACTIVE");
			seat(id, viewer, "VIEWER", "ACTIVE");
			return new Team(id, owner, admin, agent, viewer);
		});
	}

	/** 64 lower-case hex characters, different every time: the tests share one database, so fixed values would collide. */
	private static String randomHash() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
	}

	private static String guest() {
		return "guest." + UUID.randomUUID() + "@example.com";
	}

	private String status(UUID invitationId) {
		return jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitationId);
	}

	private int audited(String eventType, UUID invitationId) {
		return count("SELECT count(*) FROM audit_event WHERE event_type = ? AND target_id = ?", eventType, invitationId);
	}

	private int queuedEmails(UUID invitationId) {
		return count("SELECT count(*) FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'invitationId' = ?", invitationId.toString());
	}

	// ---- create

	@Test
	void anOwnerCreatesAPendingInvitationWithNoTokenYetAnEmailQueuedAndAnAuditRow() {
		Team team = team();
		String email = guest();

		InvitationView view = service.create(team.owner().session(), team.companyId(), email, Role.AGENT);

		assertThat(view.status()).isEqualTo(InvitationStatus.PENDING);
		assertThat(view.role()).isEqualTo(Role.AGENT);
		assertThat(view.emailSent()).as("the token is minted at delivery").isFalse();
		assertThat(jdbc.queryForObject("SELECT token_hash IS NULL FROM invitation WHERE id = ?", Boolean.class,
				view.id())).isTrue();
		Instant expiry = jdbc.queryForObject("SELECT expires_at FROM invitation WHERE id = ?", java.sql.Timestamp.class,
				view.id()).toInstant();
		assertThat(Duration.between(Instant.now(), expiry)).isBetween(Duration.ofDays(7).minusMinutes(1),
				Duration.ofDays(7).plusMinutes(1));
		assertThat(queuedEmails(view.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'invitationId' = ?", String.class, view.id().toString()))
			.as("identifiers only: no address, no token").doesNotContain(email, "token");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'invitation.created' AND company_id = ? "
				+ "AND actor_id = ? AND target_id = ? AND after_value = 'AGENT'", team.companyId(),
				team.owner().userId(), view.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM audit_event a WHERE target_id = ?", String.class,
				view.id())).as("no address in the audit row").doesNotContain(email);
	}

	@Test
	void anAdminInvitesAgentsAndViewersButNotAdminsAndNobodyInvitesAnOwner() {
		Team team = team();
		AuthenticatedUser admin = team.admin().session();

		service.create(admin, team.companyId(), guest(), Role.AGENT);
		service.create(admin, team.companyId(), guest(), Role.VIEWER);

		assertThatThrownBy(() -> service.create(admin, team.companyId(), guest(), Role.ADMIN))
			.isInstanceOfSatisfying(PermissionDeniedException.class, e -> assertThat(e.pendingAudit()).get()
				.extracting(PermissionDeniedException.PendingAudit::permission)
				.isEqualTo(Permission.INVITE_MEMBERS));
		assertThatThrownBy(() -> service.create(admin, team.companyId(), guest(), Role.OWNER))
			.isInstanceOf(ValidationFailedException.class);
		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), guest(), Role.OWNER))
			.isInstanceOf(ValidationFailedException.class);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isEqualTo(2);
	}

	@Test
	void invitingAnAdminNeedsARecentLoginAndNothingIsCreatedWithoutOne() {
		Team team = team();

		assertThatThrownBy(() -> service.create(team.owner().staleSession(), team.companyId(), guest(), Role.ADMIN))
			.isInstanceOf(ReauthenticationRequiredException.class);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isZero();
		assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'INVITATION_EMAIL' "
				+ "AND payload->>'companyId' = ?", team.companyId().toString())).isZero();

		service.create(team.owner().session(), team.companyId(), guest(), Role.ADMIN);
		service.create(team.owner().staleSession(), team.companyId(), guest(), Role.AGENT);
	}

	@Test
	void anInvalidAddressOrRoleIsAFieldErrorAndAMissingRoleIsRequired() {
		Team team = team();

		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), "not an address", Role.AGENT))
			.isInstanceOfSatisfying(ValidationFailedException.class,
					e -> assertThat(e.errors()).extracting("field", "code").containsExactly(
							org.assertj.core.groups.Tuple.tuple("email", "INVALID_EMAIL")));
		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), guest(), null))
			.isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors()).extracting("code")
				.containsExactly("REQUIRED"));
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isZero();
	}

	@Test
	void anAgentAndAViewerAreRefusedBeforeAnyLockAndBeforeAnythingIsCounted() throws Exception {
		Team team = team();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
			locks.lockCompany(team.companyId());
			held.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		try {
			assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

			for (Person weak : List.of(team.agent(), team.viewer())) {
				assertThatThrownBy(() -> service.create(weak.session(), team.companyId(), guest(), Role.AGENT))
					.as("refused, not queued for the held lock").isInstanceOf(PermissionDeniedException.class);
			}
		}
		finally {
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			pool.shutdownNow();
		}
		assertThat(limiter.current(InvitationRateLimits.PER_COMPANY, team.companyId().toString()).count())
			.as("the company's allowance was not used").isZero();
		assertThat(limiter.current(InvitationRateLimits.PER_INVITER, team.agent().userId().toString()).count()).isZero();
	}

	@Test
	void anOutsiderGetsNotFoundAndCannotUseUpTheCompanysAllowance() {
		Team team = team();
		Person outsider = newUser();

		assertThatThrownBy(() -> service.create(outsider.session(), team.companyId(), guest(), Role.AGENT))
			.isInstanceOf(CompanyNotFoundException.class);

		assertThat(limiter.current(InvitationRateLimits.PER_COMPANY, team.companyId().toString()).count()).isZero();
	}

	@Test
	void anAddressThatIsAlreadyAnActiveOrSuspendedMemberCannotBeInvitedButARemovedOneCan() {
		Team team = team();
		Person suspended = newUser();
		Person removed = newUser();
		tx.executeWithoutResult(s -> {
			seat(team.companyId(), suspended, "AGENT", "SUSPENDED");
			seat(team.companyId(), removed, "AGENT", "REMOVED");
		});

		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), team.agent().email(), Role.VIEWER))
			.as("an ACTIVE member").isInstanceOf(InvitationConflictException.class);
		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), suspended.email(), Role.VIEWER))
			.as("a SUSPENDED member (reactivate instead)").isInstanceOf(InvitationConflictException.class);
		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(),
				"  " + team.viewer().email().toUpperCase() + " ", Role.AGENT))
			.as("matching is on the normalized address").isInstanceOf(InvitationConflictException.class);
		InvitationView again = service.create(team.owner().session(), team.companyId(), removed.email(), Role.VIEWER);
		assertThat(again.status()).as("a removed member can be invited again").isEqualTo(InvitationStatus.PENDING);
	}

	@Test
	void aSecondPendingInvitationForTheSameAddressIsRefusedUntilTheFirstExpires() {
		Team team = team();
		String email = guest();
		InvitationView first = service.create(team.owner().session(), team.companyId(), email, Role.AGENT);

		assertThatThrownBy(() -> service.create(team.admin().session(), team.companyId(), email, Role.VIEWER))
			.isInstanceOf(InvitationConflictException.class);

		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() - interval '1 day' WHERE id = ?", first.id());
		InvitationView second = service.create(team.admin().session(), team.companyId(), email, Role.VIEWER);

		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(status(first.id())).as("the expired one is marked and keeps no token").isEqualTo("EXPIRED");
		assertThat(audited("invitation.expired", first.id())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ? "
				+ "AND status = 'PENDING'", team.companyId(), email)).isEqualTo(1);
	}

	@Test
	void noInvitationOperationIsAllowedWhileTheCompanyIsPendingDeletion() {
		Team team = team();
		InvitationView existing = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", team.companyId());

		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.resend(team.owner().session(), team.companyId(), existing.id()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.revoke(team.owner().session(), team.companyId(), existing.id()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThat(status(existing.id())).isEqualTo("PENDING");
	}

	// ---- send limits (AC-INV-23)

	@Test
	void the21stInvitationByOneInviterWithinAnHourIsRejected() {
		Team team = team();

		for (int i = 0; i < 20; i++) {
			service.create(team.owner().session(), team.companyId(), guest(), Role.VIEWER);
		}

		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), guest(), Role.VIEWER))
			.isInstanceOf(RateLimitedException.class);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isEqualTo(20);
		service.create(team.admin().session(), team.companyId(), guest(), Role.VIEWER);
	}

	@Test
	void the51stInvitationForOneCompanyWithinADayIsRejected() {
		Team team = team();
		List<Person> inviters = new ArrayList<>(List.of(team.owner(), team.admin()));
		Person second = newUser();
		tx.executeWithoutResult(s -> seat(team.companyId(), second, "ADMIN", "ACTIVE"));
		inviters.add(second);

		for (int i = 0; i < 50; i++) {
			service.create(inviters.get(i % 3).session(), team.companyId(), guest(), Role.VIEWER);
		}

		assertThatThrownBy(() -> service.create(inviters.get(0).session(), team.companyId(), guest(), Role.VIEWER))
			.isInstanceOf(RateLimitedException.class);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isEqualTo(50);
	}

	@Test
	void the4thSendToOneRecipientWithin24HoursIsRejectedAndResendsCount() {
		Team team = team();
		String email = guest();
		InvitationView invitation = service.create(team.owner().session(), team.companyId(), email, Role.AGENT);
		service.resend(team.owner().session(), team.companyId(), invitation.id());
		service.resend(team.owner().session(), team.companyId(), invitation.id());

		assertThatThrownBy(() -> service.resend(team.owner().session(), team.companyId(), invitation.id()))
			.isInstanceOf(RateLimitedException.class);

		assertThat(queuedEmails(invitation.id())).as("create and two resends sent").isEqualTo(3);
		service.revoke(team.owner().session(), team.companyId(), invitation.id());
		assertThatThrownBy(() -> service.create(team.admin().session(), team.companyId(), email, Role.AGENT))
			.as("with nothing pending, a new invitation to the same address is limited by the rate, not by a conflict")
			.isInstanceOf(RateLimitedException.class);
	}

	@Test
	void oneCompanyCannotUseUpAnotherCompanysAllowanceForTheSameAddress() {
		Team first = team();
		Team second = team();
		String email = guest();
		InvitationView invitation = service.create(first.owner().session(), first.companyId(), email, Role.AGENT);
		service.resend(first.owner().session(), first.companyId(), invitation.id());
		service.resend(first.owner().session(), first.companyId(), invitation.id());
		assertThatThrownBy(() -> service.resend(first.owner().session(), first.companyId(), invitation.id()))
			.isInstanceOf(RateLimitedException.class);

		InvitationView other = service.create(second.owner().session(), second.companyId(), email, Role.VIEWER);

		assertThat(other.id()).as("the other company can still invite the same address (D-164)").isNotNull();
		assertThat(queuedEmails(other.id())).isEqualTo(1);
	}

	@Test
	void aCeilingAcrossAllCompaniesStopsMassMailingToOneAddress() {
		Team team = team();
		String email = guest();
		for (int i = 0; i < 10; i++) {
			limiter.hit(InvitationRateLimits.PER_RECIPIENT_ANYWHERE, email);
		}

		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), email, Role.AGENT))
			.isInstanceOf(RateLimitedException.class);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ?", team.companyId(),
				email)).isZero();
	}

	@Test
	void requestsRefusedBeforeAnEmailIsQueuedDoNotUseUpTheRecipientAllowance() {
		Team team = team();
		String email = guest();
		InvitationView first = service.create(team.owner().session(), team.companyId(), email, Role.AGENT);
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), email, Role.AGENT))
				.as("already pending").isInstanceOf(InvitationConflictException.class);
		}
		service.revoke(team.owner().session(), team.companyId(), first.id());
		InvitationView second = service.create(team.owner().session(), team.companyId(), email, Role.AGENT);
		service.revoke(team.owner().session(), team.companyId(), second.id());
		InvitationView third = service.create(team.owner().session(), team.companyId(), email, Role.AGENT);
		service.revoke(team.owner().session(), team.companyId(), third.id());

		assertThatThrownBy(() -> service.create(team.owner().session(), team.companyId(), email, Role.AGENT))
			.as("only the three that queued an email were counted")
			.isInstanceOf(RateLimitedException.class);
	}

	// ---- resend

	@Test
	void resendingClearsTheTokenStartsANewExpiryQueuesAnEmailAndAuditsIt() {
		Team team = team();
		InvitationView invitation = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		String oldHash = randomHash();
		jdbc.update("UPDATE invitation SET token_hash = ?, expires_at = created_at + interval '1 hour' WHERE id = ?",
				oldHash, invitation.id());

		InvitationView resent = service.resend(team.admin().session(), team.companyId(), invitation.id());

		assertThat(resent.emailSent()).isFalse();
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", oldHash))
			.as("the old token stopped working in the same transaction").isZero();
		Instant expiry = jdbc.queryForObject("SELECT expires_at FROM invitation WHERE id = ?", java.sql.Timestamp.class,
				invitation.id()).toInstant();
		assertThat(Duration.between(Instant.now(), expiry)).as("a new 7 days").isGreaterThan(Duration.ofDays(7).minusMinutes(1));
		assertThat(queuedEmails(invitation.id())).isEqualTo(2);
		assertThat(audited("invitation.resent", invitation.id())).isEqualTo(1);
	}

	@Test
	void anExpiredButStillPendingInvitationCanBeResent() {
		Team team = team();
		InvitationView invitation = service.create(team.owner().session(), team.companyId(), guest(), Role.VIEWER);
		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() - interval '1 day' WHERE id = ?", invitation.id());

		InvitationView resent = service.resend(team.owner().session(), team.companyId(), invitation.id());

		assertThat(resent.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
		assertThat(status(invitation.id())).isEqualTo("PENDING");
	}

	@Test
	void anAdminCannotResendOrRevokeAnAdminInvitationAndResendingOneNeedsARecentLogin() {
		Team team = team();
		InvitationView adminInvitation = service.create(team.owner().session(), team.companyId(), guest(), Role.ADMIN);

		assertThatThrownBy(() -> service.resend(team.admin().session(), team.companyId(), adminInvitation.id()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.revoke(team.admin().session(), team.companyId(), adminInvitation.id()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.resend(team.owner().staleSession(), team.companyId(), adminInvitation.id()))
			.isInstanceOf(ReauthenticationRequiredException.class);
		service.revoke(team.owner().staleSession(), team.companyId(), adminInvitation.id());
		assertThat(status(adminInvitation.id())).as("revoking needs no recent login").isEqualTo("REVOKED");
	}

	@Test
	void anInvitationThatIsNotPendingCannotBeResentOrRevokedAgain() {
		Team team = team();
		InvitationView invitation = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		service.revoke(team.owner().session(), team.companyId(), invitation.id());

		assertThatThrownBy(() -> service.resend(team.owner().session(), team.companyId(), invitation.id()))
			.isInstanceOf(InvitationConflictException.class);
		assertThatThrownBy(() -> service.revoke(team.owner().session(), team.companyId(), invitation.id()))
			.isInstanceOf(InvitationConflictException.class);
		assertThat(audited("invitation.revoked", invitation.id())).isEqualTo(1);
	}

	// ---- revoke

	@Test
	void revokingMakesItFinalClearsTheTokenAndIsAudited() {
		Team team = team();
		InvitationView invitation = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		String hash = randomHash();
		jdbc.update("UPDATE invitation SET token_hash = ? WHERE id = ?", hash, invitation.id());

		service.revoke(team.admin().session(), team.companyId(), invitation.id());

		assertThat(status(invitation.id())).isEqualTo("REVOKED");
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", hash)).isZero();
		assertThat(jdbc.queryForObject("SELECT revoked_by FROM invitation WHERE id = ?", UUID.class, invitation.id()))
			.isEqualTo(team.admin().userId());
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'invitation.revoked' AND target_id = ? "
				+ "AND before_value = 'PENDING' AND after_value = 'REVOKED'", invitation.id())).isEqualTo(1);
	}

	@Test
	void anInvitationOfAnotherCompanyIsNotFoundAndAnAgentOrOutsiderIsRefused() {
		Team mine = team();
		Team theirs = team();
		InvitationView foreign = service.create(theirs.owner().session(), theirs.companyId(), guest(), Role.AGENT);

		assertThatThrownBy(() -> service.resend(mine.owner().session(), mine.companyId(), foreign.id()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.revoke(mine.owner().session(), mine.companyId(), foreign.id()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.revoke(theirs.agent().session(), theirs.companyId(), foreign.id()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.revoke(mine.owner().session(), theirs.companyId(), foreign.id()))
			.as("an Owner of another company").isInstanceOf(CompanyNotFoundException.class);
		assertThat(status(foreign.id())).isEqualTo("PENDING");
	}

	// ---- list

	@Test
	void theListShowsOnlyThisCompanysPendingUnexpiredInvitationsNewestFirst() {
		Team team = team();
		Team other = team();
		InvitationView first = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		InvitationView revoked = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		InvitationView expired = service.create(team.owner().session(), team.companyId(), guest(), Role.AGENT);
		InvitationView last = service.create(team.owner().session(), team.companyId(), guest(), Role.VIEWER);
		service.revoke(team.owner().session(), team.companyId(), revoked.id());
		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() - interval '1 day' WHERE id = ?", expired.id());
		service.create(other.owner().session(), other.companyId(), guest(), Role.AGENT);

		List<InvitationView> list = service.listPending(team.companyId());

		assertThat(list).extracting(InvitationView::id).containsExactly(last.id(), first.id());
		assertThat(list.toString()).as("a view never carries a token or its hash").doesNotContain("token");
	}

	// ---- concurrency

	@Test
	void manyCreationsOfTheSameInvitationAtOnceLeaveExactlyOnePending() throws Exception {
		Team team = team();
		String email = guest();
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			Person who = i % 2 == 0 ? team.owner() : team.admin();
			tasks.add(() -> {
				try {
					service.create(who.session(), team.companyId(), email, Role.VIEWER);
					return "created";
				}
				catch (InvitationConflictException e) {
					return "conflict";
				}
			});
		}

		List<String> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter("created"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter("conflict"::equals).count()).as("the allowance counts only the one that queued")
			.isEqualTo(7);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ?",
				team.companyId(), email)).isEqualTo(1);
		assertThat(queuedEmails(jdbc.queryForObject("SELECT id FROM invitation WHERE email_normalized = ?", UUID.class,
				email))).isEqualTo(1);
	}

}
