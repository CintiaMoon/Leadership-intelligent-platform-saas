package com.example.saas.api.tenant.ownership;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.ownership.application.TransferView;
import com.example.saas.api.tenant.application.PlanRestrictedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ownership transfers across subscription states (plan section 3a, Q-14; AC-PLAN-20): starting, accepting and cancelling
 * are allowed while the company is read-only or has no plan, and refused once the plan has ended, before any lock.
 */
class OwnershipPlanGatingTests extends OwnershipTestBase {

	private static final Duration WINDOW = Duration.ofHours(336);

	/** Replaces the company's subscription: a trial that started {@code startedAgo} ago, or none. */
	private void giveTrialStartedAgo(UUID companyId, Duration startedAgo) {
		Instant start = clock.instant().minus(startedAgo);
		tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM company_subscription WHERE company_id = ?", companyId);
			jdbc.update("INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, "
					+ "read_only_ends_at, state_changed_at, created_at, updated_at) VALUES (?, (SELECT id FROM plan "
					+ "WHERE code = 'TRIAL' AND version = 1), 'TRIALING', ?, ?, ?, now(), now(), now())", companyId,
					Timestamp.from(start), Timestamp.from(start.plus(WINDOW)), Timestamp.from(start.plus(WINDOW).plus(WINDOW)));
		});
	}

	private void giveNoPlan(UUID companyId) {
		tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM company_subscription WHERE company_id = ?", companyId);
			jdbc.update("INSERT INTO company_subscription (company_id, state, state_changed_at, created_at, updated_at) "
					+ "VALUES (?, 'NO_PLAN', now(), now(), now())", companyId);
		});
	}

	private static Duration readOnlyAge() {
		return WINDOW.plusHours(1);
	}

	private static Duration lockedAge() {
		return WINDOW.multipliedBy(2).plusHours(1);
	}

	@Test
	void whileReadOnlyOrWithoutAPlanATransferCanBeStartedAcceptedAndCancelled() {
		for (int round = 0; round < 2; round++) {
			Team team = team();
			if (round == 0) {
				giveTrialStartedAgo(team.companyId(), readOnlyAge());
			}
			else {
				giveNoPlan(team.companyId());
			}

			TransferView first = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
			TransferView cancelled = service.cancel(session(team.owner()), team.companyId(), first.id());
			TransferView second = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
			service.accept(session(team.admin()), team.companyId(), second.id());

			assertThat(transferStatus(first.id())).isEqualTo("CANCELLED");
			assertThat(cancelled.id()).isEqualTo(first.id());
			assertThat(transferStatus(second.id())).isEqualTo("COMPLETED");
			assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
			assertThat(owners(team.companyId())).isEqualTo(1);
		}
	}

	@Test
	void onceThePlanHasEndedNoTransferCanBeStartedAcceptedOrCancelled() {
		Team team = team();
		TransferView pending = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		giveTrialStartedAgo(team.companyId(), lockedAge());

		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.agentSeat()))
			.isInstanceOfSatisfying(PlanRestrictedException.class,
					e -> assertThat(e.state()).isEqualTo(SubscriptionState.EXPIRED_LOCKED));
		assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId(), pending.id()))
			.isInstanceOf(PlanRestrictedException.class);
		assertThatThrownBy(() -> service.accept(session(team.admin()), team.companyId(), pending.id()))
			.isInstanceOf(PlanRestrictedException.class);

		assertThat(transferStatus(pending.id())).isEqualTo("PENDING");
		assertThat(owners(team.companyId())).isEqualTo(1);
		assertThat(roleOf(team.companyId(), team.owner())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void theTransferIsRefusedAtTheLastInstantOfTheReadOnlyWindow() {
		Team team = team();
		TransferView pending = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		// The read-only window ends in one second.
		giveTrialStartedAgo(team.companyId(), WINDOW.multipliedBy(2).minusSeconds(1));

		TransferView cancelled = service.cancel(session(team.owner()), team.companyId(), pending.id());
		assertThat(transferStatus(cancelled.id())).isEqualTo("CANCELLED");
		clock.advance(Duration.ofSeconds(1));

		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.adminSeat()))
			.isInstanceOf(PlanRestrictedException.class);
	}

}
