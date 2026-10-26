package com.example.saas.api.tenant.ownership;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-OWN-20 and D-187 (security and code review): initiating fails closed when the counter store is unavailable, and
 * a store that fails only after the transfer was committed does not turn a created transfer into an error.
 */
@Import(OwnershipFailClosedTests.SwitchableStore.class)
class OwnershipFailClosedTests extends OwnershipTestBase {

	static class Switch implements RateLimiter {

		final AtomicBoolean readsDown = new AtomicBoolean();

		final AtomicBoolean hitsDown = new AtomicBoolean();

		@Override
		public RateLimitResult hit(RateLimitPolicy policy, String identifier) {
			if (hitsDown.get()) {
				throw new RateLimitStoreUnavailableException(new RuntimeException("connection refused"));
			}
			return allowed(policy);
		}

		@Override
		public RateLimitResult current(RateLimitPolicy policy, String identifier) {
			if (readsDown.get()) {
				throw new RateLimitStoreUnavailableException(new RuntimeException("connection refused"));
			}
			return allowed(policy);
		}

		private static RateLimitResult allowed(RateLimitPolicy policy) {
			return new RateLimitResult(true, 1, policy.limit(), Instant.now().plus(policy.window()));
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SwitchableStore {

		@Bean
		@Primary
		Switch switchableRateLimiter() {
			return new Switch();
		}

	}

	@Autowired
	private Switch store;

	@AfterEach
	void restoreStore() {
		store.readsDown.set(false);
		store.hitsDown.set(false);
	}

	@Test
	void initiatingIsRefusedAndCreatesNothingWhileTheCounterStoreIsDown() {
		Team team = team();
		store.readsDown.set(true);

		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.adminSeat()))
			.isInstanceOf(RateLimitStoreUnavailableException.class);

		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();
		assertThat(audits("ownership.transfer.initiated", team.companyId())).isZero();
		assertThat(count("SELECT count(*) FROM outbox_event WHERE payload->>'companyId' = ?", team.companyId().toString()))
			.isZero();

		store.readsDown.set(false);
		assertThat(service.initiate(session(team.owner()), team.companyId(), team.adminSeat()).id())
			.as("works again once the store is back").isNotNull();
	}

	@Test
	void aStoreThatFailsAfterTheCommitDoesNotUndoOrReportAnErrorForTheCreatedTransfer() {
		Team team = team();
		store.hitsDown.set(true);

		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());

		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
		assertThat(audits("ownership.transfer.initiated", team.companyId())).isEqualTo(1);
		assertThat(events("OWNERSHIP_TRANSFER_INITIATED", view.id())).as("one notice, to the recipient").isEqualTo(1);
	}

	@Test
	void cancellingAndAcceptingDoNotUseTheCounterStoreAtAll() {
		Team team = team();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		store.readsDown.set(true);
		store.hitsDown.set(true);

		service.accept(session(team.admin()), team.companyId(), view.id());

		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
	}

}
