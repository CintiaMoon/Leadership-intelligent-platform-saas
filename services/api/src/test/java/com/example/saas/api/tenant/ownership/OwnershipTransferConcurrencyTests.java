package com.example.saas.api.tenant.ownership;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.MembershipService;
import com.example.saas.api.tenant.ownership.application.OwnershipConflictException;
import com.example.saas.api.tenant.ownership.application.OwnershipEvents;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-OWN-04, AC-OWN-05 and AC-OWN-10 under real concurrency on PostgreSQL (D-32, D-182, plan Q-18). Whatever the
 * interleaving, a company has exactly one Owner membership, a transfer is resolved once, and the outcomes are exactly
 * the ones some serial order would give.
 */
class OwnershipTransferConcurrencyTests extends OwnershipTestBase {

	@Autowired
	private MembershipService membershipService;

	private static String outcome(Callable<?> action) {
		try {
			action.call();
			return "ok";
		}
		catch (OwnershipConflictException e) {
			return "conflict";
		}
		catch (Exception e) {
			return e.getClass().getSimpleName();
		}
	}

	@Test
	void eightSimultaneousAcceptancesOfOneTransferCompleteItExactlyOnce() throws Exception {
		Team team = team();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> outcome(() -> service.accept(session(team.admin()), team.companyId(), view.id())));
		}

		List<String> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter("ok"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(7);
		assertThat(owners(team.companyId())).isEqualTo(1);
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("ADMIN:ACTIVE");
		assertThat(transferStatus(view.id())).isEqualTo("COMPLETED");
		assertThat(audits("ownership.transfer.completed", team.companyId())).isEqualTo(1);
		assertThat(events(OwnershipEvents.COMPLETED, view.id())).isEqualTo(2);
	}

	@Test
	void simultaneousInitiationsToDifferentMembersLeaveExactlyOnePendingTransfer() throws Exception {
		Team team = team();
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			long seat = i % 3;
			tasks.add(() -> outcome(() -> service.initiate(session(team.owner()), team.companyId(),
					seat == 0 ? team.adminSeat() : seat == 1 ? team.agentSeat() : team.viewerSeat())));
		}

		List<String> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter("ok"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(5);
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ? AND status = 'PENDING'",
				team.companyId())).isEqualTo(1);
		assertThat(owners(team.companyId())).isEqualTo(1);
	}

	@Test
	void anAcceptanceRacingACancellationEndsInOneOfTwoSerialOrders() throws Exception {
		for (int round = 0; round < 6; round++) {
			Team team = team();
			TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
			Callable<String> accept = () -> outcome(() -> service.accept(session(team.admin()), team.companyId(), view.id()));
			Callable<String> cancel = () -> outcome(() -> service.cancel(session(team.owner()), team.companyId(), view.id()));

			List<String> results = Parallel.runAll(List.of(accept, cancel));

			boolean accepted = results.get(0).equals("ok");
			// If the acceptance came first, the former Owner is an Admin when the cancellation runs: it is refused either
			// as no longer allowed (403, the early check saw the new role) or as not pending (409, the check under the
			// lock did). Both are the serial order "accept, then cancel"; which one depends on timing.
			if (accepted) {
				assertThat(results.get(1)).as("round %d", round).isIn("conflict", "PermissionDeniedException");
			}
			else {
				assertThat(results.get(0)).as("round %d", round).isEqualTo("conflict");
				assertThat(results.get(1)).isEqualTo("ok");
			}
			assertThat(owners(team.companyId())).isEqualTo(1);
			assertThat(transferStatus(view.id())).isEqualTo(accepted ? "COMPLETED" : "CANCELLED");
			assertThat(roleOf(team.companyId(), team.admin())).isEqualTo(accepted ? "OWNER:ACTIVE" : "ADMIN:ACTIVE");
			assertThat(roleOf(team.companyId(), team.owner())).isEqualTo(accepted ? "ADMIN:ACTIVE" : "OWNER:ACTIVE");
		}
	}

	@Test
	void anAcceptanceRacingTheSuspensionOfTheRecipientEndsInOneOfTwoSerialOrders() throws Exception {
		for (int round = 0; round < 6; round++) {
			Team team = team();
			TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
			Callable<String> accept = () -> outcome(() -> service.accept(session(team.admin()), team.companyId(), view.id()));
			Callable<String> suspend = () -> outcome(
					() -> membershipService.suspend(session(team.owner()), team.companyId(), team.adminSeat()));

			List<String> results = Parallel.runAll(List.of(accept, suspend));

			boolean accepted = results.get(0).equals("ok");
			assertThat(owners(team.companyId())).as("round %d", round).isEqualTo(1);
			if (accepted) {
				// The recipient became the Owner first; the Owner membership cannot then be suspended.
				assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
				assertThat(results.get(1)).isNotEqualTo("ok");
				assertThat(transferStatus(view.id())).isEqualTo("COMPLETED");
			}
			else {
				// The suspension came first, so the recipient could not accept and nothing changed hands.
				assertThat(results.get(1)).isEqualTo("ok");
				assertThat(results.get(0)).as("refused as a missing recipient or under the lock")
					.isIn("CompanyNotFoundException", "conflict");
				assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("ADMIN:SUSPENDED");
				assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
				assertThat(transferStatus(view.id())).isEqualTo("PENDING");
			}
		}
	}

	@Test
	void anInitiationRacingTheSweepExpiresTheOverdueTransferOnceAndCreatesTheNewOne() throws Exception {
		for (int round = 0; round < 5; round++) {
			Team team = team();
			TransferView old = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
			clock.advance(Duration.ofDays(7));
			Callable<String> initiate = () -> outcome(
					() -> service.initiate(session(team.owner()), team.companyId(), team.agentSeat()));
			Callable<String> expire = () -> outcome(sweep::runOnce);

			List<String> results = Parallel.runAll(List.of(initiate, expire));

			// Whichever of the two expires the old transfer, it is expired exactly once (the initiation expires an overdue
			// transfer itself, so this does not prove the sweep ran first).
			assertThat(results).as("round %d", round).containsExactly("ok", "ok");
			assertThat(transferStatus(old.id())).isEqualTo("EXPIRED");
			assertThat(audits("ownership.transfer.expired", team.companyId())).as("expired once").isEqualTo(1);
			assertThat(events(OwnershipEvents.EXPIRED, old.id())).isEqualTo(2);
			assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ? AND status = 'PENDING'",
					team.companyId())).isEqualTo(1);
			assertThat(owners(team.companyId())).isEqualTo(1);
		}
	}

	@Test
	void anAcceptanceWaitsForARecipientAccountThatIsBeingDisabledAndThenIsRefused() throws Exception {
		// The operator's disable procedure locks only the companies the account owns, so it does not queue behind the
		// company lock the acceptance holds. The acceptance must therefore read the account under a lock (security
		// review): it waits for the update in flight and sees the account disabled.
		Team team = team();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		CompletableFuture<String> accepted = new CompletableFuture<>();

		tx.executeWithoutResult(status -> {
			jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", team.admin().userId());
			CompletableFuture.runAsync(() -> accepted
				.complete(outcome(() -> service.accept(session(team.admin()), team.companyId(), view.id()))));
			// The test's lock timeout is short, so change the account as soon as the acceptance is seen waiting for it.
			awaitABlockedSession();
			assertThat(accepted.isDone()).as("the acceptance waits for the account row").isFalse();
			jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.admin().userId());
		});

		assertThat(accepted.get(20, TimeUnit.SECONDS)).isEqualTo("conflict");
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("ADMIN:ACTIVE");
		assertThat(owners(team.companyId())).isEqualTo(1);
		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
	}

	/**
	 * Waits until another session is blocked on a row lock. The test holds the only lock in play, so the blocked session
	 * is the one under test. (The statement text of a waiting session is not reliable enough to match on.)
	 */
	private void awaitABlockedSession() {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbc.queryForObject(
					"SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()",
					Integer.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new AssertionError("no session was blocked on a lock");
	}

}
