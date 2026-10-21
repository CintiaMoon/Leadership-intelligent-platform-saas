package com.example.saas.api.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** Races that must resolve the same way every time, run against PostgreSQL (CLAUDE.md principle 15). */
class TenantConcurrencyTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	private <T> List<T> inParallel(int threads, Callable<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CyclicBarrier start = new CyclicBarrier(threads);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static String sqlState(DataAccessException e) {
		Throwable cause = e;
		while (cause != null && !(cause instanceof java.sql.SQLException)) {
			cause = cause.getCause();
		}
		return cause == null ? "none" : ((java.sql.SQLException) cause).getSQLState();
	}

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private void addMember(UUID companyId, UUID userId, String role) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'ACTIVE', now(), now())", companyId, userId, role);
	}

	private UUID companyWithOwner(UUID owner) {
		return tx.execute(s -> {
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Race', now(), now()) RETURNING id",
					UUID.class);
			addMember(companyId, owner, "OWNER");
			return companyId;
		});
	}

	@Test
	void parallelCreationsByOneAccountRespectTheDailyLimitExactly() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		String csrf = api.sessionCsrf(cookie, freshIp());

		// Each attempt comes from its own address, so only the per-account limit (10 per day) decides.
		List<Integer> statuses = inParallel(25, () -> {
			MvcResult result = api.postWithSession("/api/v1/companies", json("name", "Parallel"), cookie, csrf,
					freshIp());
			return result.getResponse().getStatus();
		});

		assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(10);
		assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(15);
		assertThat(count("SELECT count(*) FROM membership WHERE user_id = ? AND role = 'OWNER'", userId)).isEqualTo(10);
	}

	@Test
	void parallelOwnershipTransfersLeaveExactlyOneOwner() throws Exception {
		UUID owner = newUser();
		UUID first = newUser();
		UUID second = newUser();
		UUID companyId = companyWithOwner(owner);
		addMember(companyId, first, "ADMIN");
		addMember(companyId, second, "ADMIN");
		List<UUID> successors = List.of(first, second);
		java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

		// Both transactions hand the company to a different successor. The slice-003/005 service will lock the company
		// row first; here the unique Owner index and the Owner trigger alone must keep the invariant.
		List<Boolean> committed = inParallel(2, () -> {
			UUID successor = successors.get(next.getAndIncrement());
			try {
				tx.executeWithoutResult(s -> {
					jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ? AND user_id = ?",
							companyId, owner);
					jdbc.update("UPDATE membership SET role = 'OWNER' WHERE company_id = ? AND user_id = ?",
							companyId, successor);
				});
				return true;
			}
			catch (DataAccessException e) {
				// The loser must lose to the Owner invariant, not to a deadlock or a timeout.
				assertThat(sqlState(e)).as("why the losing transfer failed").isIn("23505", "23000");
				return false;
			}
		});

		assertThat(committed).as("one transfer wins, the other is refused").containsExactlyInAnyOrder(true, false);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
				+ "AND status IN ('ACTIVE', 'SUSPENDED')", companyId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' AND user_id = ?",
				companyId, owner)).isZero();
	}

}
