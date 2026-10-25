package com.example.saas.api.tenant.ownership;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

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
			assertThat(results.get(1)).as("round %d: exactly one of the two wins", round)
				.isEqualTo(accepted ? "conflict" : "ok");
			assertThat(results.get(0)).isIn("ok", "conflict");
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

			assertThat(results.get(0)).as("round %d", round).isEqualTo("ok");
			assertThat(transferStatus(old.id())).isEqualTo("EXPIRED");
			assertThat(audits("ownership.transfer.expired", team.companyId())).as("expired once").isEqualTo(1);
			assertThat(events(OwnershipEvents.EXPIRED, old.id())).isEqualTo(2);
			assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ? AND status = 'PENDING'",
					team.companyId())).isEqualTo(1);
			assertThat(owners(team.companyId())).isEqualTo(1);
		}
	}

}
