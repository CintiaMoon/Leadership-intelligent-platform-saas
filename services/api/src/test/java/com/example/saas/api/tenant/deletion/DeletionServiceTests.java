package com.example.saas.api.tenant.deletion;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.ratelimit.RateLimitedException;
import com.example.saas.api.security.ReauthenticationRequiredException;
import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.deletion.application.CompanyDeletionRequested;
import com.example.saas.api.tenant.deletion.application.DeletionConflictException;
import com.example.saas.api.tenant.deletion.application.DeletionEvents;
import com.example.saas.api.tenant.deletion.application.DeletionView;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requesting and cancelling the deletion of a company against PostgreSQL (SPEC-DEL, plan Phase 2): AC-DEL-01, 09, 10,
 * 13, 15, 17, 19, 20, 25 (the notice), 28. The lock timeout is raised so that a request can wait for the company lock
 * while another transaction holds it.
 */
@RecordApplicationEvents
@TestPropertySource(properties = "app.tenant.lock-timeout=10s")
class DeletionServiceTests extends DeletionTestBase {

	private static final Duration GRACE = Duration.ofDays(30);

	@Autowired
	private ApplicationEvents applicationEvents;

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private OwnershipTransferService transfers;

	// ---- the request

	@Test
	void theOwnerRequestsAndTheCompanyIsPendingWithTheDeadlineThirtyDaysLater() {
		Team team = team();

		DeletionView view = service.request(session(team.owner()), team.companyId());

		assertThat(view.status()).isEqualTo(CompanyStatus.PENDING_DELETION);
		assertThat(view.requestedAt()).isEqualTo(clock.instant());
		assertThat(view.purgeAt()).isEqualTo(clock.instant().plus(GRACE));
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
		assertThat(jdbc.queryForObject("SELECT deletion_requested_at FROM company WHERE id = ?", Timestamp.class,
				team.companyId()).toInstant()).isEqualTo(clock.instant());
		assertThat(jdbc.queryForObject("SELECT purge_at FROM company WHERE id = ?", Timestamp.class, team.companyId())
			.toInstant()).isEqualTo(clock.instant().plus(GRACE));
		assertThat(jdbc.queryForObject("SELECT deletion_requested_by FROM company WHERE id = ?", UUID.class,
				team.companyId())).isEqualTo(team.owner().userId());
		assertThat(jdbc.queryForList("SELECT before_value || '>' || after_value FROM audit_event WHERE event_type = ? "
				+ "AND company_id = ? AND actor_id = ?", String.class, DeletionEvents.AUDIT_REQUESTED, team.companyId(),
				team.owner().userId())).containsExactly("ACTIVE>PENDING_DELETION");
		assertThat(applicationEvents.stream(CompanyDeletionRequested.class)
			.filter(e -> e.companyId().equals(team.companyId()))
			.map(CompanyDeletionRequested::purgeAt)).containsExactly(clock.instant().plus(GRACE));
	}

	@Test
	void onlyAnActiveOwnerCanRequestAndNothingChangesOtherwise() {
		Team team = team();
		Person outsider = person("Eve Outsider");
		UUID suspendedSeat = tx.execute(s -> {
			Person suspended = person("Sam Suspended");
			seat(team.companyId(), suspended, "AGENT", "SUSPENDED");
			return suspended.userId();
		});

		assertThatThrownBy(() -> service.request(session(team.admin()), team.companyId()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.request(session(team.agent()), team.companyId()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.request(session(team.viewer()), team.companyId()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.request(session(outsider), team.companyId()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.request(
				new com.example.saas.api.security.AuthenticatedUser(suspendedSeat, clock.instant(),
						clock.instant().plus(Duration.ofHours(1))),
				team.companyId())).isInstanceOf(CompanyNotFoundException.class);

		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");
		assertThat(audits(DeletionEvents.AUDIT_REQUESTED, team.companyId())).isZero();
		assertThat(events(DeletionEvents.REQUESTED, team.companyId())).isZero();
	}

	@Test
	void aRequestNeedsAnAuthenticationFromTheLastTenMinutes() {
		Team team = team();

		assertThatThrownBy(() -> service.request(staleSession(team.owner()), team.companyId()))
			.isInstanceOf(ReauthenticationRequiredException.class);

		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");
	}

	@Test
	void aCompanyFlaggedForOwnershipResolutionCannotBeRequestedAndANonActiveOneNeitherDoes() {
		Team flagged = team();
		jdbc.update("UPDATE company SET ownership_resolution_at = now() WHERE id = ?", flagged.companyId());
		assertThatThrownBy(() -> service.request(session(flagged.owner()), flagged.companyId()))
			.isInstanceOf(DeletionConflictException.class);
		assertThat(statusOf(flagged.companyId())).isEqualTo("ACTIVE");

		Team pending = team();
		service.request(session(pending.owner()), pending.companyId());
		assertThatThrownBy(() -> service.request(session(pending.owner()), pending.companyId()))
			.as("a second request is refused by the policy (every state change but the cancellation, AC-DEL-12)")
			.isInstanceOf(PermissionDeniedException.class);
		assertThat(audits(DeletionEvents.AUDIT_REQUESTED, pending.companyId())).isEqualTo(1);
	}

	@Test
	void aPendingOwnershipTransferEndsWithTheRequestAndIsAudited() {
		Team team = team();
		UUID transferId = transfers.initiate(session(team.owner()), team.companyId(), team.adminSeat()).id();

		service.request(session(team.owner()), team.companyId());

		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, transferId))
			.isEqualTo("CANCELLED");
		assertThat(audits("ownership.transfer.cancelled", team.companyId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, team.ownerSeat()))
			.as("nobody changed hands").isEqualTo("OWNER");
	}

	@Test
	void eightSimultaneousRequestsProduceExactlyOneTransition() throws Exception {
		Team team = team();
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> {
				try {
					service.request(session(team.owner()), team.companyId());
					return "ok";
				}
				catch (DeletionConflictException | PermissionDeniedException conflict) {
					// Either the policy already sees the pending state, or the check under the lock does.
					return "conflict";
				}
			});
		}

		List<String> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter("ok"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(7);
		assertThat(audits(DeletionEvents.AUDIT_REQUESTED, team.companyId())).isEqualTo(1);
		assertThat(events(DeletionEvents.REQUESTED, team.companyId())).as("the Owner and the Admin, once").isEqualTo(2);
	}

	@Test
	void aRequestThatWaitedForTheLockIsRefusedWhenTheOwnerChangedMeanwhile() throws Exception {
		Team team = team();
		CompletableFuture<Throwable> request = new CompletableFuture<>();

		tx.executeWithoutResult(s -> {
			locks.lockCompany(team.companyId());
			CompletableFuture.runAsync(() -> {
				try {
					service.request(session(team.owner()), team.companyId());
					request.complete(null);
				}
				catch (Throwable thrown) {
					request.complete(thrown);
				}
			});
			sleep(1500);
			assertThat(request.isDone()).as("the request waits for the company lock").isFalse();
			// Meanwhile the Owner hands the company to the Admin.
			jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE id = ?", team.ownerSeat());
			jdbc.update("UPDATE membership SET role = 'OWNER' WHERE id = ?", team.adminSeat());
		});

		assertThat(request.get(20, TimeUnit.SECONDS)).isInstanceOf(PermissionDeniedException.class);
		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");
		assertThat(audits(DeletionEvents.AUDIT_REQUESTED, team.companyId())).isZero();
	}

	@Test
	void aCancellationThatWaitedForTheLockIsRefusedWhenTheOwnerChangedMeanwhile() throws Exception {
		Team team = team();
		service.request(session(team.owner()), team.companyId());
		CompletableFuture<Throwable> cancel = new CompletableFuture<>();

		tx.executeWithoutResult(s -> {
			locks.lockCompany(team.companyId());
			CompletableFuture.runAsync(() -> {
				try {
					service.cancel(session(team.owner()), team.companyId());
					cancel.complete(null);
				}
				catch (Throwable thrown) {
					cancel.complete(thrown);
				}
			});
			sleep(1500);
			assertThat(cancel.isDone()).as("the cancellation waits for the company lock").isFalse();
			jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE id = ?", team.ownerSeat());
			jdbc.update("UPDATE membership SET role = 'OWNER' WHERE id = ?", team.adminSeat());
		});

		assertThat(cancel.get(20, TimeUnit.SECONDS)).isInstanceOf(PermissionDeniedException.class);
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
		assertThat(audits(DeletionEvents.AUDIT_CANCELLED, team.companyId())).isZero();
	}

	@Test
	void aFailureWhileQueueingTheEmailsRollsTheWholeRequestBack() {
		Team team = team();
		UUID transferId = transfers.initiate(session(team.owner()), team.companyId(), team.adminSeat()).id();
		jdbc.execute("CREATE OR REPLACE FUNCTION fail_deletion_outbox() RETURNS trigger AS $$ BEGIN "
				+ "IF NEW.event_type LIKE 'COMPANY_DELETION_%' THEN RAISE EXCEPTION 'outbox down'; END IF; RETURN NEW; END; "
				+ "$$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER trg_fail_deletion_outbox BEFORE INSERT ON outbox_event FOR EACH ROW "
				+ "EXECUTE FUNCTION fail_deletion_outbox()");
		try {
			assertThatThrownBy(() -> service.request(session(team.owner()), team.companyId()))
				.hasMessageContaining("outbox down");
		}
		finally {
			jdbc.execute("DROP TRIGGER trg_fail_deletion_outbox ON outbox_event");
			jdbc.execute("DROP FUNCTION fail_deletion_outbox()");
		}

		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");
		assertThat(audits(DeletionEvents.AUDIT_REQUESTED, team.companyId())).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, transferId))
			.as("the transfer is still pending").isEqualTo("PENDING");
		assertThat(audits("ownership.transfer.cancelled", team.companyId())).isZero();
	}

	// ---- the emails

	@Test
	void theOwnerAndTheAdminsGetTheRequestAndEveryOtherActiveMemberGetsTheNotice() {
		Team team = team();
		Person suspended = person("Sam Suspended");
		Person removed = person("Rae Removed");
		Person secondAdmin = person("Ann Admin");
		tx.executeWithoutResult(s -> {
			seat(team.companyId(), suspended, "AGENT", "SUSPENDED");
			seat(team.companyId(), removed, "VIEWER", "REMOVED");
			seat(team.companyId(), secondAdmin, "ADMIN", "ACTIVE");
		});

		service.request(session(team.owner()), team.companyId());

		assertThat(events(DeletionEvents.REQUESTED, team.companyId())).isEqualTo(3);
		assertThat(eventsTo(DeletionEvents.REQUESTED, team.companyId(), team.owner())).isEqualTo(1);
		assertThat(eventsTo(DeletionEvents.REQUESTED, team.companyId(), team.admin())).isEqualTo(1);
		assertThat(eventsTo(DeletionEvents.REQUESTED, team.companyId(), secondAdmin)).isEqualTo(1);
		assertThat(events(DeletionEvents.NOTICE, team.companyId())).isEqualTo(2);
		assertThat(eventsTo(DeletionEvents.NOTICE, team.companyId(), team.agent())).isEqualTo(1);
		assertThat(eventsTo(DeletionEvents.NOTICE, team.companyId(), team.viewer())).isEqualTo(1);
		assertThat(eventsTo(DeletionEvents.NOTICE, team.companyId(), suspended)).isZero();
		assertThat(eventsTo(DeletionEvents.REQUESTED, team.companyId(), removed)).isZero();
	}

	@Test
	void theEmailsAreSentWithTheCompanyNameAndTheDateAndNothingElseSecret() {
		Team team = team();
		service.request(session(team.owner()), team.companyId());

		drainOutbox();

		var toOwner = mail.sentTo(team.owner().email());
		assertThat(toOwner).hasSize(1);
		assertThat(toOwner.get(0).subject()).isEqualTo("A company deletion was requested");
		assertThat(toOwner.get(0).body()).contains("\"Closing Co\"").contains("(UTC)")
			.contains("/companies/" + team.companyId());
		assertThat(mail.sentTo(team.admin().email())).hasSize(1);
		var toAgent = mail.sentTo(team.agent().email());
		assertThat(toAgent).hasSize(1);
		assertThat(toAgent.get(0).subject()).isEqualTo("A company you belong to is scheduled for deletion");
		assertThat(mail.sentTo(team.viewer().email())).hasSize(1);
	}

	@Test
	void aRequestEmailDeliveredAfterTheCancellationIsDroppedAndTheCancellationIsAnnounced() {
		Team team = team();
		service.request(session(team.owner()), team.companyId());
		clock.advance(Duration.ofSeconds(5));
		service.cancel(session(team.owner()), team.companyId());

		drainOutbox();

		assertThat(mail.sentTo(team.owner().email())).extracting(m -> m.subject())
			.containsExactly("A company deletion was cancelled");
		assertThat(mail.sentTo(team.admin().email())).extracting(m -> m.subject())
			.containsExactly("A company deletion was cancelled");
		assertThat(mail.sentTo(team.agent().email())).as("the notice no longer applies").isEmpty();
		assertThat(mail.sentTo(team.viewer().email())).isEmpty();
	}

	// ---- the cancellation

	@Test
	void theOwnerCancelsBeforeTheDeadlineWithoutARecentAuthenticationAndTheCompanyIsActiveAgain() {
		Team team = team();
		service.request(session(team.owner()), team.companyId());
		clock.advance(Duration.ofDays(3));

		DeletionView view = service.cancel(staleSession(team.owner()), team.companyId());

		assertThat(view.status()).isEqualTo(CompanyStatus.ACTIVE);
		assertThat(view.purgeAt()).isNull();
		assertThat(jdbc.queryForMap("SELECT status, deletion_requested_at, deletion_requested_by, purge_at FROM company "
				+ "WHERE id = ?", team.companyId())).containsEntry("status", "ACTIVE")
			.containsEntry("deletion_requested_at", null)
			.containsEntry("purge_at", null);
		assertThat(jdbc.queryForList("SELECT before_value || '>' || after_value FROM audit_event WHERE event_type = ? "
				+ "AND company_id = ?", String.class, DeletionEvents.AUDIT_CANCELLED, team.companyId()))
			.containsExactly("PENDING_DELETION>ACTIVE");
		assertThat(events(DeletionEvents.CANCELLED, team.companyId())).as("the Owner and the Admin").isEqualTo(2);
		assertThat(eventsTo(DeletionEvents.CANCELLED, team.companyId(), team.agent())).isZero();
	}

	@Test
	void onlyTheOwnerCancelsAndAnActiveCompanyHasNothingToCancel() {
		Team team = team();
		assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId()))
			.as("nothing is pending").isInstanceOf(DeletionConflictException.class);

		service.request(session(team.owner()), team.companyId());

		assertThatThrownBy(() -> service.cancel(session(team.admin()), team.companyId()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.cancel(session(person("Eve Outsider")), team.companyId()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
	}

	@Test
	void theDeadlineDecidesACancellationToTheMillisecond() {
		for (long offsetMillis : new long[] { -1, 0, 1 }) {
			Team team = team();
			service.request(session(team.owner()), team.companyId());
			Instant purgeAt = clock.instant().plus(GRACE);
			clock.set(purgeAt.plusMillis(offsetMillis));

			if (offsetMillis < 0) {
				assertThatCode(() -> service.cancel(session(team.owner()), team.companyId()))
					.as("1 ms before").doesNotThrowAnyException();
				assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");
			}
			else {
				assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId()))
					.as("%d ms after the deadline", offsetMillis).isInstanceOf(DeletionConflictException.class);
				assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
			}
		}
	}

	@Test
	void aCancelledCompanyCanBeRequestedAgain() {
		Team team = team();
		service.request(session(team.owner()), team.companyId());
		clock.advance(Duration.ofMinutes(5));
		service.cancel(session(team.owner()), team.companyId());
		clock.advance(Duration.ofMinutes(5));

		DeletionView again = service.request(session(team.owner()), team.companyId());

		assertThat(again.purgeAt()).isEqualTo(clock.instant().plus(GRACE));
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
	}

	// ---- the limits

	@Test
	void aRequestAndCancelLoopSendsAtMostOneEmailWaveOfEachKindPerHourAndAuditsEveryStep() {
		Team team = team();
		for (int i = 0; i < 2; i++) {
			service.request(session(team.owner()), team.companyId());
			clock.advance(Duration.ofSeconds(10));
			service.cancel(session(team.owner()), team.companyId());
			clock.advance(Duration.ofSeconds(10));
		}
		service.request(session(team.owner()), team.companyId());

		assertThat(audits(DeletionEvents.AUDIT_REQUESTED, team.companyId())).isEqualTo(3);
		assertThat(audits(DeletionEvents.AUDIT_CANCELLED, team.companyId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type LIKE 'company.deletion.%' "
				+ "AND reason_code = 'emails_held_back'", team.companyId())).as("the 2nd and 3rd request, the 2nd cancel")
			.isEqualTo(3);
		assertThat(events(DeletionEvents.REQUESTED, team.companyId())).as("one wave to the Owner and the Admin").isEqualTo(2);
		assertThat(events(DeletionEvents.NOTICE, team.companyId())).isEqualTo(2);
		assertThat(events(DeletionEvents.CANCELLED, team.companyId())).isEqualTo(2);
	}

	@Test
	void theCooldownEndsAfterAnHour() {
		Team team = team();
		service.request(session(team.owner()), team.companyId());
		service.cancel(session(team.owner()), team.companyId());
		clock.advance(Duration.ofMinutes(61));

		service.request(session(team.owner()), team.companyId());

		assertThat(events(DeletionEvents.REQUESTED, team.companyId())).isEqualTo(4);
	}

	@Test
	void theSixthOperationInAnHourByOneUserIsRefusedAndChangesNothing() {
		Team team = team();
		for (int i = 0; i < 2; i++) {
			service.request(session(team.owner()), team.companyId());
			clock.advance(Duration.ofSeconds(10));
			service.cancel(session(team.owner()), team.companyId());
			clock.advance(Duration.ofSeconds(10));
		}
		service.request(session(team.owner()), team.companyId());
		clock.advance(Duration.ofSeconds(10));
		assertThat(statusOf(team.companyId())).as("five operations are within the limit").isEqualTo("PENDING_DELETION");

		assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId()))
			.isInstanceOf(RateLimitedException.class);
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

}
