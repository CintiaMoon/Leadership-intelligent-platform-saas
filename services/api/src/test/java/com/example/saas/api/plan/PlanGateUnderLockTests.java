package com.example.saas.api.plan;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.MembershipService;
import com.example.saas.api.tenant.application.PlanRestrictedException;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The subscription state is decided again once the company lock is held (plan Q-10; Phase 6 review of Phase 3). A request
 * passes the early check while the company is running, then waits for the lock another transaction holds; that
 * transaction moves the company to EXPIRED_LOCKED (as the clock or an operator would) and commits. The waiting request must
 * then be refused. Without the second check it would go through on a state that is no longer true, and no other test
 * would notice, because the early check refuses first in every test that sets the state before the request.
 */
// The test profile shortens the lock wait to 500 ms; these tests need a request to wait for the lock for a second or two.
@TestPropertySource(properties = "app.tenant.lock-timeout=10s")
class PlanGateUnderLockTests extends PlanTestBase {

	private static final Duration WINDOW = Duration.ofHours(336);

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private MembershipService memberships;

	@Autowired
	private InvitationService invitations;

	@Autowired
	private OwnershipTransferService transfers;

	private UUID seat(UUID companyId, String role, String status) {
		UUID user = newUser();
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, user, role, status);
	}

	private UUID userOf(UUID membershipId) {
		return jdbc.queryForObject("SELECT user_id FROM membership WHERE id = ?", UUID.class, membershipId);
	}

	/** What the clock does when the read-only window ends: the instants move, the stored state is left to the sweep. */
	private void lockTheCompany(UUID companyId) {
		Instant start = clock.instant().minus(WINDOW.multipliedBy(2).plusHours(1));
		jdbc.update("UPDATE company_subscription SET trial_started_at = ?, trial_ends_at = ?, read_only_ends_at = ? "
				+ "WHERE company_id = ?", Timestamp.from(start), Timestamp.from(start.plus(WINDOW)),
				Timestamp.from(start.plus(WINDOW).plus(WINDOW)), companyId);
	}

	/**
	 * Runs the request on another thread while this one holds the company lock, locks the company in the meantime and
	 * returns the request's failure.
	 */
	private Throwable refusedWhileTheStateChangesUnderTheLock(UUID companyId, Supplier<Object> request) throws Exception {
		CompletableFuture<Object> pending = new CompletableFuture<>();
		tx.executeWithoutResult(s -> {
			locks.lockCompany(companyId);
			CompletableFuture.runAsync(() -> {
				try {
					pending.complete(request.get());
				}
				catch (RuntimeException e) {
					pending.completeExceptionally(e);
				}
			});
			sleep(1500);
			assertThat(pending.isDone()).as("the request passed the early check and waits for the company lock: %s", pending.isCompletedExceptionally() ? pending.handle((v, e) -> e).join() : "completed").isFalse();
			lockTheCompany(companyId);
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

	private void assertRestrictedAsLocked(Throwable failure) {
		assertThat(failure).isInstanceOfSatisfying(PlanRestrictedException.class,
				e -> assertThat(e.state()).isEqualTo(SubscriptionState.EXPIRED_LOCKED));
	}

	@Test
	void aReactivationThatWaitedForTheLockIsRefusedWhenTheCompanyWasLockedMeanwhile() throws Exception {
		UUID owner = newUser();
		UUID companyId = createCompany(owner).id();
		UUID suspended = seat(companyId, "AGENT", "SUSPENDED");

		Throwable failure = refusedWhileTheStateChangesUnderTheLock(companyId,
				() -> memberships.reactivate(session(owner), companyId, suspended));

		assertRestrictedAsLocked(failure);
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, suspended))
			.isEqualTo("SUSPENDED");
	}

	@Test
	void anInvitationThatWaitedForTheLockIsRefusedWhenTheCompanyWasLockedMeanwhile() throws Exception {
		UUID owner = newUser();
		UUID companyId = createCompany(owner).id();

		Throwable failure = refusedWhileTheStateChangesUnderTheLock(companyId,
				() -> invitations.create(session(owner), companyId, "late.hire@example.com", Role.AGENT));

		assertRestrictedAsLocked(failure);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", companyId)).isZero();
	}

	@Test
	void anOwnershipTransferThatWaitedForTheLockIsRefusedWhenTheCompanyWasLockedMeanwhile() throws Exception {
		UUID owner = newUser();
		UUID companyId = createCompany(owner).id();
		UUID adminSeat = seat(companyId, "ADMIN", "ACTIVE");

		Throwable failure = refusedWhileTheStateChangesUnderTheLock(companyId,
				() -> transfers.initiate(session(owner), companyId, adminSeat));

		assertRestrictedAsLocked(failure);
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", companyId)).isZero();
	}

	@Test
	void aCancellationAndAnAcceptanceThatWaitedForTheLockAreRefusedWhenTheCompanyWasLockedMeanwhile() throws Exception {
		UUID owner = newUser();
		UUID companyId = createCompany(owner).id();
		UUID adminSeat = seat(companyId, "ADMIN", "ACTIVE");
		UUID admin = userOf(adminSeat);
		TransferView pending = transfers.initiate(session(owner), companyId, adminSeat);

		Throwable cancel = refusedWhileTheStateChangesUnderTheLock(companyId,
				() -> transfers.cancel(session(owner), companyId, pending.id()));
		assertRestrictedAsLocked(cancel);

		// Back to a running trial for the second request, which is accepted by the recipient.
		jdbc.update("UPDATE company_subscription SET trial_started_at = ?, trial_ends_at = ?, read_only_ends_at = ? "
				+ "WHERE company_id = ?", Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plus(WINDOW)),
				Timestamp.from(clock.instant().plus(WINDOW).plus(WINDOW)), companyId);
		Throwable accept = refusedWhileTheStateChangesUnderTheLock(companyId,
				() -> transfers.accept(session(admin), companyId, pending.id()));
		assertRestrictedAsLocked(accept);

		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, pending.id()))
			.isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, adminSeat))
			.isEqualTo("ADMIN");
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
