package com.example.saas.api.tenant.deletion;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.saas.api.auth.RecordingEmailSender;
import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.invitation.application.InvitationAcceptService;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.invitation.application.InvitationView;
import com.example.saas.api.tenant.TestLifecycle;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.MembershipService;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The company's lifecycle is decided again once the company lock is held (SPEC-DEL plan Q-08; AC-DEL-12). A write passes
 * the early check while the company is ACTIVE, then waits for the lock another transaction holds; that transaction records
 * a deletion request, as the Owner's would, and commits. The waiting write must then be refused. Without the second check
 * it would go through on a state that is no longer true, and no other test would notice, because the early check refuses
 * first in every test that sets the state before the request. One test per write path.
 */
// The test profile shortens the lock wait to 500 ms; these tests need a request to wait for the lock for a second or two.
@TestPropertySource(properties = "app.tenant.lock-timeout=10s")
class PendingDeletionUnderLockTests extends DeletionTestBase {

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private MembershipService memberships;

	@Autowired
	private InvitationService invitations;

	@Autowired
	private InvitationAcceptService accepting;

	@Autowired
	private OwnershipTransferService transfers;

	/**
	 * Runs the write on another thread while this one holds the company lock, records the deletion request in the meantime
	 * and returns the write's failure (the test fails if it succeeded).
	 */
	private Throwable refusedWhileTheCompanyCloses(UUID companyId, Supplier<Object> write) throws Exception {
		CompletableFuture<Object> pending = new CompletableFuture<>();
		tx.executeWithoutResult(s -> {
			locks.lockCompany(companyId);
			CompletableFuture.runAsync(() -> {
				try {
					pending.complete(write.get());
				}
				catch (RuntimeException e) {
					pending.completeExceptionally(e);
				}
			});
			sleep(1500);
			assertThat(pending.isDone()).as("the write passed the early check and waits for the company lock: %s",
					pending.isCompletedExceptionally() ? pending.handle((v, e) -> e).join() : "").isFalse();
			TestLifecycle.markPendingDeletion(jdbc, companyId);
		});
		assertThatThrownBy(() -> pending.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
		try {
			pending.get();
			return null;
		}
		catch (ExecutionException e) {
			return e.getCause();
		}
	}

	private String roleOf(UUID seatId) {
		return jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, seatId);
	}

	private String statusOfSeat(UUID seatId) {
		return jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, seatId);
	}

	@Test
	void aRoleChangeThatWaitedForTheLockIsRefusedWhenTheDeletionWasRequestedMeanwhile() throws Exception {
		Team team = team();

		Throwable failure = refusedWhileTheCompanyCloses(team.companyId(),
				() -> memberships.changeRole(session(team.owner()), team.companyId(), team.agentSeat(), Role.VIEWER));

		assertThat(failure).isNotNull();
		assertThat(roleOf(team.agentSeat())).isEqualTo("AGENT");
	}

	@Test
	void aSuspensionAndARemovalThatWaitedForTheLockAreRefusedWhenTheDeletionWasRequestedMeanwhile() throws Exception {
		Team suspend = team();
		assertThat(refusedWhileTheCompanyCloses(suspend.companyId(),
				() -> memberships.suspend(session(suspend.owner()), suspend.companyId(), suspend.agentSeat()))).isNotNull();
		assertThat(statusOfSeat(suspend.agentSeat())).isEqualTo("ACTIVE");

		Team remove = team();
		assertThat(refusedWhileTheCompanyCloses(remove.companyId(),
				() -> {
					memberships.remove(session(remove.owner()), remove.companyId(), remove.viewerSeat());
					return "removed";
				})).isNotNull();
		assertThat(statusOfSeat(remove.viewerSeat())).isEqualTo("ACTIVE");
	}

	@Test
	void aReactivationThatWaitedForTheLockIsRefusedWhenTheDeletionWasRequestedMeanwhile() throws Exception {
		Team team = team();
		Person suspended = person("Sam Suspended");
		UUID suspendedSeat = tx.execute(s -> seat(team.companyId(), suspended, "AGENT", "SUSPENDED"));

		Throwable failure = refusedWhileTheCompanyCloses(team.companyId(),
				() -> memberships.reactivate(session(team.owner()), team.companyId(), suspendedSeat));

		assertThat(failure).isNotNull();
		assertThat(statusOfSeat(suspendedSeat)).isEqualTo("SUSPENDED");
	}

	@Test
	void leavingThatWaitedForTheLockIsRefusedWhenTheDeletionWasRequestedMeanwhile() throws Exception {
		Team team = team();

		Throwable failure = refusedWhileTheCompanyCloses(team.companyId(), () -> {
			memberships.leave(session(team.agent()), team.companyId());
			return "left";
		});

		assertThat(failure).isNotNull();
		assertThat(statusOfSeat(team.agentSeat())).isEqualTo("ACTIVE");
	}

	@Test
	void anInvitationCreatedResentOrRevokedAfterWaitingForTheLockIsRefusedWhenTheDeletionWasRequestedMeanwhile()
			throws Exception {
		Team create = team();
		assertThat(refusedWhileTheCompanyCloses(create.companyId(),
				() -> invitations.create(session(create.owner()), create.companyId(), "late.hire@example.com", Role.AGENT)))
			.isNotNull();
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", create.companyId())).isZero();

		Team resend = team();
		InvitationView existing = invitations.create(session(resend.owner()), resend.companyId(), "resend.me@example.com",
				Role.AGENT);
		assertThat(refusedWhileTheCompanyCloses(resend.companyId(),
				() -> invitations.resend(session(resend.owner()), resend.companyId(), existing.id()))).isNotNull();

		Team revoke = team();
		InvitationView toRevoke = invitations.create(session(revoke.owner()), revoke.companyId(), "revoke.me@example.com",
				Role.AGENT);
		assertThat(refusedWhileTheCompanyCloses(revoke.companyId(), () -> {
			invitations.revoke(session(revoke.owner()), revoke.companyId(), toRevoke.id());
			return "revoked";
		})).isNotNull();
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, toRevoke.id()))
			.isEqualTo("PENDING");
	}

	@Test
	void anAcceptanceThatWaitedForTheLockIsRefusedWhenTheDeletionWasRequestedMeanwhile() throws Exception {
		Team control = team();
		String controlEmail = AuthApi.uniqueEmail();
		invitations.create(session(control.owner()), control.companyId(), controlEmail, Role.AGENT);
		drainOutbox();
		String controlToken = RecordingEmailSender.tokenIn(mail.sentTo(controlEmail).get(0));
		mail.clear();
		UUID controlInvitee = registerVerified(controlEmail, STRONG_PASSWORD);
		assertThat(accepting.accept(controlInvitee, controlToken, AuthApi.freshIp()).companyId())
			.as("the same steps with no deletion in between are accepted").isEqualTo(control.companyId());

		Team team = team();
		String email = AuthApi.uniqueEmail();
		invitations.create(session(team.owner()), team.companyId(), email, Role.AGENT);
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		mail.clear();
		UUID invitee = registerVerified(email, STRONG_PASSWORD);

		Throwable failure = refusedWhileTheCompanyCloses(team.companyId(),
				() -> accepting.accept(invitee, token, AuthApi.freshIp()));

		assertThat(failure).isNotNull();
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", team.companyId(), invitee))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE company_id = ?", String.class, team.companyId()))
			.isEqualTo("PENDING");
	}

	@Test
	void anOwnershipTransferStartedCancelledOrAcceptedAfterWaitingForTheLockIsRefusedWhenTheDeletionWasRequestedMeanwhile()
			throws Exception {
		Team start = team();
		assertThat(refusedWhileTheCompanyCloses(start.companyId(),
				() -> transfers.initiate(session(start.owner()), start.companyId(), start.adminSeat()))).isNotNull();
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", start.companyId())).isZero();

		Team cancel = team();
		TransferView toCancel = transfers.initiate(session(cancel.owner()), cancel.companyId(), cancel.adminSeat());
		assertThat(refusedWhileTheCompanyCloses(cancel.companyId(),
				() -> transfers.cancel(session(cancel.owner()), cancel.companyId(), toCancel.id()))).isNotNull();
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, toCancel.id()))
			.isEqualTo("PENDING");

		Team accept = team();
		TransferView toAccept = transfers.initiate(session(accept.owner()), accept.companyId(), accept.adminSeat());
		assertThat(refusedWhileTheCompanyCloses(accept.companyId(),
				() -> transfers.accept(session(accept.admin()), accept.companyId(), toAccept.id()))).isNotNull();
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, toAccept.id()))
			.isEqualTo("PENDING");
		assertThat(roleOf(accept.adminSeat())).isEqualTo("ADMIN");
		assertThat(roleOf(accept.ownerSeat())).isEqualTo("OWNER");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
