package com.example.saas.api.tenant;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.TestEndpointsConfiguration;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lock order and the lock timeout of D-125, against PostgreSQL. The test profile sets the timeout to 500 ms. */
@Import({ TestEndpointsConfiguration.class, TenantTestEndpointsConfiguration.class })
class CompanyLocksTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private CompanyLocks locks;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private record Seeded(UUID companyId, UUID ownerMembershipId, UUID ownerId) {
	}

	private Seeded company(UUID owner) {
		return tx.execute(s -> {
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Locks', now(), now()) RETURNING id",
					UUID.class);
			UUID membershipId = jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, "
					+ "created_at, updated_at) VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now()) RETURNING id", UUID.class,
					companyId, owner);
			return new Seeded(companyId, membershipId, owner);
		});
	}

	/** Holds the company lock in another thread until released. */
	private static final class Holder implements AutoCloseable {

		private final ExecutorService pool = Executors.newSingleThreadExecutor();

		private final CountDownLatch held = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private final Future<?> work;

		Holder(TransactionTemplate tx, CompanyLocks locks, UUID companyId) throws InterruptedException {
			work = pool.submit(() -> tx.executeWithoutResult(s -> {
				locks.lockCompany(companyId);
				held.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(held.await(10, TimeUnit.SECONDS)).as("the other transaction took the lock").isTrue();
		}

		@Override
		public void close() throws Exception {
			release.countDown();
			work.get(10, TimeUnit.SECONDS);
			pool.shutdownNow();
		}

	}

	@Test
	void aSecondTransactionWaitsOnlyUntilTheTimeoutAndThenFailsWithoutChangingAnything() throws Exception {
		Seeded seeded = company(newUser());

		try (Holder holder = new Holder(tx, locks, seeded.companyId())) {
			long started = System.nanoTime();
			assertThatThrownBy(() -> tx.executeWithoutResult(s -> locks.lockCompany(seeded.companyId())))
				.isInstanceOf(PessimisticLockingFailureException.class);
			long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

			assertThat(waitedMillis).as("waited about the 500 ms timeout, not forever").isBetween(300L, 5_000L);
		}
		// Once the holder has committed the lock is free again.
		UUID relocked = tx.execute(s -> locks.lockCompany(seeded.companyId()).getId());
		assertThat(relocked).isEqualTo(seeded.companyId());
	}

	@Test
	void theTimeoutLastsForOneTransactionOnly() {
		Seeded seeded = company(newUser());

		String during = tx.execute(s -> {
			locks.lockCompany(seeded.companyId());
			return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
		});
		String after = tx.execute(s -> jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class));

		assertThat(during).isEqualTo("500ms");
		assertThat(after).as("SET LOCAL must not leak to the next user of the pooled connection").isEqualTo("0");
	}

	@Test
	void aMembershipOfAnotherCompanyLooksLikeAMissingOne() {
		Seeded mine = company(newUser());
		Seeded theirs = company(newUser());

		UUID locked = tx.execute(s -> {
			locks.lockCompany(mine.companyId());
			return locks.lockMembership(mine.companyId(), mine.ownerMembershipId()).getId();
		});
		assertThat(locked).isEqualTo(mine.ownerMembershipId());
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			locks.lockCompany(mine.companyId());
			locks.lockMembership(mine.companyId(), theirs.ownerMembershipId());
		})).isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> locks.lockCompany(UUID.randomUUID())))
			.isInstanceOf(CompanyNotFoundException.class);
	}

	@Test
	void theLocksCannotBeTakenOutsideATransaction() {
		Seeded seeded = company(newUser());

		assertThatThrownBy(() -> locks.lockCompany(seeded.companyId()))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> locks.lockMembership(seeded.companyId(), seeded.ownerMembershipId()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void aLockTimeoutIsReportedAs503WithRetryAfter() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		Seeded seeded = company(userId);

		try (Holder holder = new Holder(tx, locks, seeded.companyId())) {
			MvcResult result = api.get("/api/v1/companies/" + seeded.companyId() + "/test/lock-company", cookie,
					freshIp());

			assertThat(result.getResponse().getStatus()).isEqualTo(503);
			assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
			assertThat(AuthApi.parse(result).get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		}
	}

}
