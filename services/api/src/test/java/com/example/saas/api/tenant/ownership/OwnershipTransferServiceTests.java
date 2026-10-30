package com.example.saas.api.tenant.ownership;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.tenant.TestLifecycle;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.ratelimit.RateLimitedException;
import com.example.saas.api.security.ReauthenticationRequiredException;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.application.OwnershipConflictException;
import com.example.saas.api.tenant.ownership.application.OwnershipEvents;
import com.example.saas.api.tenant.ownership.application.TransferView;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ownership transfers through the service, against PostgreSQL (SPEC-OWN; plan Phase 2). */
class OwnershipTransferServiceTests extends OwnershipTestBase {

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private InvitationService invitations;

	private TransferView startToAdmin(Team team) {
		return service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
	}

	// ---- initiating

	@Test
	void theOwnerInitiatesAPendingTransferThatChangesNothingYetAndQueuesOneEmailForTheRecipient() {
		Team team = team();

		TransferView view = startToAdmin(team);

		assertThat(view.status()).isEqualTo(TransferStatus.PENDING);
		assertThat(view.initiatorName()).isEqualTo("Olivia Owner");
		assertThat(view.recipientName()).isEqualTo("Bo Admin");
		assertThat(view.youAreInitiator()).isTrue();
		assertThat(view.youAreRecipient()).isFalse();
		assertThat(view.expiresAt()).isEqualTo(view.createdAt().plus(Duration.ofDays(7)));
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("ADMIN:ACTIVE");
		assertThat(audits("ownership.transfer.initiated", team.companyId())).isEqualTo(1);
		assertThat(events(OwnershipEvents.INITIATED, view.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type = ? "
				+ "AND payload->>'transferId' = ?", String.class, OwnershipEvents.INITIATED, view.id().toString()))
			.as("identifiers only, no address").doesNotContain("@");
	}

	@Test
	void everyoneButTheOwnerIsRefusedBeforeAnyLockAndBeforeAnythingIsCounted() throws Exception {
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
			for (Person weak : List.of(team.admin(), team.agent(), team.viewer())) {
				assertThatThrownBy(() -> service.initiate(session(weak), team.companyId(), team.agentSeat()))
					.as("%s initiates: refused, not queued for the held lock", weak.name())
					.isInstanceOfSatisfying(PermissionDeniedException.class, e -> assertThat(e.pendingAudit().orElseThrow()
						.permission()).isEqualTo(Permission.TRANSFER_OWNERSHIP));
			}
		}
		finally {
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();
	}

	@Test
	void anOutsiderAndTheOwnerOfAnotherCompanyAreToldTheCompanyDoesNotExist() {
		Team team = team();
		Team other = team();

		assertThatThrownBy(() -> service.initiate(session(person("Stranger")), team.companyId(), team.adminSeat()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.initiate(session(other.owner()), team.companyId(), team.adminSeat()))
			.isInstanceOf(CompanyNotFoundException.class);
	}

	@Test
	void initiatingNeedsALoginWithinTheLastTenMinutes() {
		Team team = team();

		assertThatThrownBy(() -> service.initiate(staleSession(team.owner()), team.companyId(), team.adminSeat()))
			.isInstanceOf(ReauthenticationRequiredException.class);

		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();
	}

	@Test
	void aRecipientMustBeAnotherActiveMemberOfThisCompanyWithAUsableAccount() {
		Team team = team();
		Team other = team();

		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.ownerSeat()))
			.as("oneself").isInstanceOf(ValidationFailedException.class);
		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), other.adminSeat()))
			.as("a membership of another company").isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), UUID.randomUUID()))
			.as("an unknown membership").isInstanceOf(CompanyNotFoundException.class);

		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.agentSeat());
		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.agentSeat()))
			.as("suspended").isInstanceOf(OwnershipConflictException.class);
		jdbc.update("UPDATE membership SET status = 'REMOVED' WHERE id = ?", team.viewerSeat());
		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.viewerSeat()))
			.as("removed").isInstanceOf(OwnershipConflictException.class);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.admin().userId());
		assertThatThrownBy(() -> startToAdmin(team)).as("account disabled").isInstanceOf(OwnershipConflictException.class);
		jdbc.update("UPDATE app_user SET status = 'ACTIVE', email_verified_at = NULL WHERE id = ?", team.admin().userId());
		assertThatThrownBy(() -> startToAdmin(team)).as("email not verified").isInstanceOf(OwnershipConflictException.class);

		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();
	}

	@Test
	void aSecondTransferWhileOneIsPendingIsRefusedButAFinishedOneDoesNotBlock() {
		Team team = team();
		TransferView first = startToAdmin(team);

		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.agentSeat()))
			.isInstanceOf(OwnershipConflictException.class);

		service.cancel(session(team.owner()), team.companyId(), first.id());
		TransferView second = service.initiate(session(team.owner()), team.companyId(), team.agentSeat());
		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(transferStatus(first.id())).isEqualTo("CANCELLED");
	}

	@Test
	void anOverdueTransferIsEndedByTheNextInitiationInsteadOfBlockingIt() {
		Team team = team();
		TransferView old = startToAdmin(team);
		clock.advance(Duration.ofDays(7));

		TransferView fresh = service.initiate(session(team.owner()), team.companyId(), team.agentSeat());

		assertThat(transferStatus(old.id())).isEqualTo("EXPIRED");
		assertThat(transferStatus(fresh.id())).isEqualTo("PENDING");
		assertThat(audits("ownership.transfer.expired", team.companyId())).isEqualTo(1);
		assertThat(events(OwnershipEvents.EXPIRED, old.id())).as("the initiator and the recipient").isEqualTo(2);
	}

	@Test
	void noMoreThanFiveTransfersCanBeInitiatedPerCompanyPerDayAndRefusedRequestsDoNotCount() {
		Team team = team();
		for (int i = 0; i < 4; i++) {
			// A refused request (a transfer is already pending) does not use the allowance up.
			TransferView view = startToAdmin(team);
			for (int refused = 0; refused < 3; refused++) {
				assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.agentSeat()))
					.isInstanceOf(OwnershipConflictException.class);
			}
			service.cancel(session(team.owner()), team.companyId(), view.id());
		}
		TransferView fifth = startToAdmin(team);
		service.cancel(session(team.owner()), team.companyId(), fifth.id());

		assertThatThrownBy(() -> startToAdmin(team)).isInstanceOf(RateLimitedException.class);

		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isEqualTo(5);
	}

	@Test
	void aCompanyBeingDeletedCannotStartATransfer() {
		Team team = team();
		TestLifecycle.markPendingDeletion(jdbc, team.companyId());

		assertThatThrownBy(() -> startToAdmin(team)).isInstanceOf(PermissionDeniedException.class);
	}

	// ---- cancelling

	@Test
	void theInitiatorCancelsAndACancelledTransferCanNoLongerBeAccepted() {
		Team team = team();
		TransferView view = startToAdmin(team);

		TransferView cancelled = service.cancel(session(team.owner()), team.companyId(), view.id());

		assertThat(cancelled.status()).isEqualTo(TransferStatus.CANCELLED);
		assertThat(audits("ownership.transfer.cancelled", team.companyId())).isEqualTo(1);
		assertThat(events(OwnershipEvents.CANCELLED, view.id())).as("the initiator and the recipient").isEqualTo(2);
		assertThatThrownBy(() -> service.accept(session(team.admin()), team.companyId(), view.id()))
			.isInstanceOf(OwnershipConflictException.class);
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void onlyTheInitiatingOwnerCancelsAndOthersAreRefusedOrToldItDoesNotExist() {
		Team team = team();
		TransferView view = startToAdmin(team);

		assertThatThrownBy(() -> service.cancel(session(team.admin()), team.companyId(), view.id()))
			.as("the recipient is not an Owner").isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.cancel(session(team.agent()), team.companyId(), view.id()))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> service.cancel(session(person("Stranger")), team.companyId(), view.id()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId(), UUID.randomUUID()))
			.isInstanceOf(CompanyNotFoundException.class);
		Team other = team();
		assertThatThrownBy(() -> service.cancel(session(other.owner()), other.companyId(), view.id()))
			.as("a transfer of another company").isInstanceOf(CompanyNotFoundException.class);
		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
	}

	@Test
	void anOverdueTransferCannotBeCancelledAndStaysPendingUntilTheSweep() {
		Team team = team();
		TransferView view = startToAdmin(team);
		clock.advance(Duration.ofDays(7));

		assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId(), view.id()))
			.isInstanceOf(OwnershipConflictException.class);

		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
	}

	// ---- accepting

	@Test
	void theRecipientAcceptsTheOldOwnerBecomesAnAdminAndExactlyOneOwnerRemains() {
		Team team = team();
		TransferView view = startToAdmin(team);

		TransferView done = service.accept(session(team.admin()), team.companyId(), view.id());

		assertThat(done.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(done.youAreRecipient()).isTrue();
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("ADMIN:ACTIVE");
		assertThat(owners(team.companyId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT resolution || ':' || (resolved_by = recipient_id) FROM ownership_transfer "
				+ "WHERE id = ?", String.class, view.id())).isEqualTo("accepted:true");
		assertThat(audits("ownership.transfer.completed", team.companyId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type = 'membership.role.changed' "
				+ "AND reason_code = 'ownership_transfer' AND ((before_value = 'OWNER' AND after_value = 'ADMIN') "
				+ "OR (before_value = 'ADMIN' AND after_value = 'OWNER')) AND actor_id = ?", team.companyId(),
				team.admin().userId())).isEqualTo(2);
		assertThat(events(OwnershipEvents.COMPLETED, view.id())).isEqualTo(2);
	}

	@Test
	void aRecipientWhoIsAnAgentOrAViewerBecomesTheOwnerToo() {
		Team team = team();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.viewerSeat());

		service.accept(session(team.viewer()), team.companyId(), view.id());

		assertThat(roleOf(team.companyId(), team.viewer())).isEqualTo("OWNER:ACTIVE");
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("ADMIN:ACTIVE");
		assertThat(owners(team.companyId())).isEqualTo(1);
	}

	@Test
	void onlyTheNamedRecipientCanAcceptAndEveryoneElseIsToldItDoesNotExist() {
		Team team = team();
		TransferView view = startToAdmin(team);

		for (Person other : List.of(team.owner(), team.agent(), team.viewer(), person("Stranger"))) {
			assertThatThrownBy(() -> service.accept(session(other), team.companyId(), view.id()))
				.as(other.name()).isInstanceOf(CompanyNotFoundException.class);
		}
		Team elsewhere = team();
		assertThatThrownBy(() -> service.accept(session(elsewhere.admin()), elsewhere.companyId(), view.id()))
			.as("the right person but another company").isInstanceOf(CompanyNotFoundException.class);
		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void acceptingNeedsALoginWithinTheLastTenMinutes() {
		Team team = team();
		TransferView view = startToAdmin(team);

		assertThatThrownBy(() -> service.accept(staleSession(team.admin()), team.companyId(), view.id()))
			.isInstanceOf(ReauthenticationRequiredException.class);

		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void aSecondAcceptanceOfTheSameTransferIsRefused() {
		Team team = team();
		TransferView view = startToAdmin(team);
		service.accept(session(team.admin()), team.companyId(), view.id());

		assertThatThrownBy(() -> service.accept(session(team.admin()), team.companyId(), view.id()))
			.isInstanceOf(OwnershipConflictException.class);

		assertThat(owners(team.companyId())).isEqualTo(1);
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void anAcceptanceAtTheExpiryIsRefusedEvenThoughTheSweepHasNotRun() {
		Team team = team();
		TransferView view = startToAdmin(team);
		clock.advance(Duration.ofDays(7).minusMillis(1));
		TransferView shownBefore = service.current(session(team.admin()), team.companyId());
		assertThat(shownBefore.status()).isEqualTo(TransferStatus.PENDING);
		clock.advance(Duration.ofMillis(1));

		assertThatThrownBy(() -> service.accept(session(team.admin()), team.companyId(), view.id()))
			.isInstanceOf(OwnershipConflictException.class);

		assertThat(transferStatus(view.id())).as("the sweep has not run").isEqualTo("PENDING");
		assertThat(service.current(session(team.admin()), team.companyId()).status()).as("but readers see it expired")
			.isEqualTo(TransferStatus.EXPIRED);
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void aRecipientWhoStoppedBeingAnActiveMemberOrWhoseAccountIsUnusableCannotAccept() {
		// A member who is no longer active is told the company does not exist (D-108); a disabled account that still
		// holds an active membership reaches the check under the lock, which refuses it.
		for (String change : List.of("membership suspended", "membership removed", "account disabled")) {
			Team team = team();
			TransferView view = startToAdmin(team);
			switch (change) {
				case "membership suspended" -> jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.adminSeat());
				case "membership removed" -> jdbc.update("UPDATE membership SET status = 'REMOVED' WHERE id = ?", team.adminSeat());
				default -> jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.admin().userId());
			}

			assertThatThrownBy(() -> service.accept(session(team.admin()), team.companyId(), view.id()))
				.as(change).isInstanceOf(change.equals("account disabled") ? OwnershipConflictException.class
						: CompanyNotFoundException.class);

			assertThat(roleOf(team.companyId(), team.owner())).as(change).isEqualTo("OWNER:ACTIVE");
			assertThat(transferStatus(view.id())).as(change).isEqualTo("PENDING");
		}
	}

	@Test
	void aCompanyThatIsBeingDeletedOrWhoseOwnerIsNoLongerActiveCannotCompleteATransfer() {
		Team deleting = team();
		TransferView first = startToAdmin(deleting);
		TestLifecycle.markPendingDeletion(jdbc, deleting.companyId());
		assertThatThrownBy(() -> service.accept(session(deleting.admin()), deleting.companyId(), first.id()))
			.as("pending deletion").isInstanceOf(OwnershipConflictException.class);

		// The operator procedure suspended the Owner and flagged the company (D-54): the initiator is no longer the active Owner.
		Team flagged = team();
		TransferView second = startToAdmin(flagged);
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", flagged.ownerSeat());
		jdbc.update("UPDATE company SET ownership_resolution_at = now() WHERE id = ?", flagged.companyId());
		assertThatThrownBy(() -> service.accept(session(flagged.admin()), flagged.companyId(), second.id()))
			.as("flagged company").isInstanceOf(OwnershipConflictException.class);
		assertThat(roleOf(flagged.companyId(), flagged.owner())).isEqualTo("OWNER:SUSPENDED");
		assertThat(owners(flagged.companyId())).isEqualTo(1);
	}

	@Test
	void completionRevokesTheOldOwnersPendingInvitationsForRolesAnAdminCannotGrantAndKeepsTheRest() {
		Team team = team();
		UUID forAdmin = invitations.create(session(team.owner()), team.companyId(), "new.admin@example.com", Role.ADMIN).id();
		UUID forAgent = invitations.create(session(team.owner()), team.companyId(), "new.agent@example.com", Role.AGENT).id();
		TransferView view = startToAdmin(team);

		service.accept(session(team.admin()), team.companyId(), view.id());

		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, forAdmin))
			.as("an Admin cannot grant Admin").isEqualTo("REVOKED");
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, forAgent))
			.as("an Admin can grant Agent").isEqualTo("PENDING");
	}

	// ---- reading (AC-OWN-22)

	@Test
	void onlyTheInitiatorAndTheRecipientCanReadATransfer() {
		Team team = team();
		TransferView view = startToAdmin(team);

		assertThat(service.current(session(team.owner()), team.companyId()).youAreInitiator()).isTrue();
		assertThat(service.current(session(team.admin()), team.companyId()).youAreRecipient()).isTrue();
		assertThat(service.get(session(team.admin()), team.companyId(), view.id()).id()).isEqualTo(view.id());
		for (Person other : List.of(team.agent(), team.viewer())) {
			assertThatThrownBy(() -> service.current(session(other), team.companyId())).as(other.name())
				.isInstanceOf(CompanyNotFoundException.class);
			assertThatThrownBy(() -> service.get(session(other), team.companyId(), view.id())).as(other.name())
				.isInstanceOf(CompanyNotFoundException.class);
		}
		Team elsewhere = team();
		assertThatThrownBy(() -> service.get(session(elsewhere.owner()), elsewhere.companyId(), view.id()))
			.isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> service.current(session(person("Stranger")), team.companyId()))
			.isInstanceOf(CompanyNotFoundException.class);
	}

	@Test
	void whenNothingIsPendingThereIsNothingToRead() {
		Team team = team();
		assertThatThrownBy(() -> service.current(session(team.owner()), team.companyId()))
			.isInstanceOf(CompanyNotFoundException.class);
	}

	// ---- the sweep (AC-OWN-14, AC-OWN-16)

	@Test
	void theSweepExpiresOverdueTransfersOnceAndLeavesTheRestAlone() {
		Team overdue = team();
		Team fresh = team();
		TransferView old = startToAdmin(overdue);
		clock.advance(Duration.ofDays(3));
		TransferView recent = startToAdmin(fresh);
		clock.advance(Duration.ofDays(4));

		// The database is shared with other tests, which may have left overdue transfers of their own: look at ours.
		for (int run = 0; run < 5 && !transferStatus(old.id()).equals("EXPIRED"); run++) {
			assertThat(sweep.runOnce()).isPositive();
		}

		assertThat(transferStatus(old.id())).isEqualTo("EXPIRED");
		assertThat(transferStatus(recent.id())).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT resolution || ':' || (resolved_by IS NULL) FROM ownership_transfer WHERE id = ?",
				String.class, old.id())).isEqualTo("expired:true");
		assertThat(audits("ownership.transfer.expired", overdue.companyId())).isEqualTo(1);
		assertThat(events(OwnershipEvents.EXPIRED, old.id())).isEqualTo(2);
		sweep.runOnce();
		assertThat(audits("ownership.transfer.expired", overdue.companyId())).as("not expired twice").isEqualTo(1);
		assertThat(events(OwnershipEvents.EXPIRED, old.id())).isEqualTo(2);
		assertThatThrownBy(() -> service.accept(session(overdue.admin()), overdue.companyId(), old.id()))
			.isInstanceOf(OwnershipConflictException.class);
	}

	// ---- the deletion hook (D-31, AC-OWN-23)

	@Test
	void aDeletionRequestCancelsThePendingTransferWithAnAuditEntryAndEmails() {
		Team team = team();
		TransferView view = startToAdmin(team);

		Boolean had = tx.execute(s -> {
			locks.lockCompany(team.companyId());
			return service.cancelPendingForDeletion(team.companyId(), team.owner().userId());
		});

		assertThat(had).isTrue();
		assertThat(transferStatus(view.id())).isEqualTo("CANCELLED");
		assertThat(jdbc.queryForObject("SELECT resolution FROM ownership_transfer WHERE id = ?", String.class, view.id()))
			.isEqualTo("cancelled_company_deletion");
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type = 'ownership.transfer.cancelled' "
				+ "AND reason_code = 'company_deletion'", team.companyId())).isEqualTo(1);
		assertThat(events(OwnershipEvents.CANCELLED, view.id())).isEqualTo(2);
		Boolean again = tx.execute(s -> {
			locks.lockCompany(team.companyId());
			return service.cancelPendingForDeletion(team.companyId(), team.owner().userId());
		});
		assertThat(again).as("nothing pending any more").isFalse();
	}

	@Test
	void theDeletionHookRecordsAnOverdueTransferAsExpiredAndNeedsATransaction() {
		Team team = team();
		TransferView view = startToAdmin(team);
		clock.advance(Duration.ofDays(8));

		tx.executeWithoutResult(s -> {
			locks.lockCompany(team.companyId());
			service.cancelPendingForDeletion(team.companyId(), team.owner().userId());
		});

		assertThat(transferStatus(view.id())).isEqualTo("EXPIRED");
		assertThatThrownBy(() -> service.cancelPendingForDeletion(team.companyId(), team.owner().userId()))
			.as("must run inside the deletion transaction").isInstanceOf(RuntimeException.class);
	}

	// ---- the emails (AC-OWN-06, AC-OWN-17)

	@Test
	void theEmailsAreFixedTextWithTheCompanyNameInTheBodyOnlyAndNoSecretOrPersonName() {
		Team team = team();
		TransferView view = startToAdmin(team);
		drainOutbox();
		List<EmailMessage> toRecipient = mail.sentTo(team.admin().email());
		assertThat(toRecipient).hasSize(1);
		assertThat(toRecipient.get(0).subject()).isEqualTo("You were asked to become an Owner").doesNotContain("Handover");
		assertThat(toRecipient.get(0).body()).contains("\"Handover Co\"", "/companies/" + team.companyId() + "/ownership")
			.doesNotContain("token", "Olivia", "Bo Admin", team.owner().email());
		assertThat(mail.sentTo(team.owner().email())).as("the initiator is not told about the initiation").isEmpty();

		service.accept(session(team.admin()), team.companyId(), view.id());
		drainOutbox();

		assertThat(mail.sentTo(team.owner().email())).singleElement().satisfies(m -> {
			assertThat(m.subject()).isEqualTo("Ownership of a company was transferred");
			assertThat(m.body()).contains("You are now an Admin").doesNotContain("Bo Admin", "Olivia");
		});
		assertThat(mail.sentTo(team.admin().email())).hasSize(2);
		assertThat(mail.sentTo(team.admin().email()).get(1).body()).contains("You are now the Owner");
		assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type = ? "
				+ "AND payload->>'addressee' IS NULL LIMIT 1", String.class, OwnershipEvents.COMPLETED))
			.as("a sent event is scrubbed").isEqualTo("{}");
	}

	@Test
	void anEmailAboutAStateTheTransferHasLeftIsNotSentAndNeitherIsOneToADisabledAccount() {
		Team team = team();
		TransferView view = startToAdmin(team);
		service.cancel(session(team.owner()), team.companyId(), view.id());
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.owner().userId());

		drainOutbox();

		assertThat(mail.sentTo(team.admin().email()).stream().map(EmailMessage::subject))
			.as("no 'asked to become an Owner' after the cancellation, only the cancellation")
			.containsExactly("An ownership transfer was cancelled");
		assertThat(mail.sentTo(team.owner().email())).as("the disabled account gets nothing").isEmpty();
	}

}
