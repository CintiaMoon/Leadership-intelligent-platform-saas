package com.example.saas.api.tenant;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.ownership.application.OwnershipConflictException;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-OWN-15 and plan Q-18: an acceptance racing the operator procedure that disables the Owner's account (D-54, D-135),
 * run from the runbook's own SQL against PostgreSQL. Either the transfer completes first, and then the account that is
 * disabled is an Admin and nothing is flagged, or the procedure runs first, and then the initiator is no longer the
 * active Owner and the acceptance is refused. A company never ends up with two Owners or none.
 */
class OwnershipTransferAndAccountDisableTests extends AuthIntegrationTest {

	private static final Path SCRIPT = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"disable-account-and-flag-companies.sql");

	@Autowired
	private OwnershipTransferService service;

	@Autowired
	private TransactionTemplate tx;

	private record Person(UUID userId, String email) {
	}

	private Person person() {
		String email = "member." + UUID.randomUUID() + "@example.com";
		UUID id = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "email_verified_at, created_at, updated_at) VALUES (?, ?, 'P', 'hash', now(), now(), now()) RETURNING id",
				UUID.class, email, email);
		return new Person(id, email);
	}

	private AuthenticatedUser session(Person person) {
		Instant now = Instant.now();
		return new AuthenticatedUser(person.userId(), now, now.plus(Duration.ofHours(12)));
	}

	@Test
	void anAcceptanceRacingTheDisableProcedureEndsInOneOfTwoSerialOrdersWithExactlyOneOwner() throws Exception {
		for (int round = 0; round < 6; round++) {
			Person owner = person();
			Person admin = person();
			UUID[] seats = new UUID[2];
			UUID companyId = tx.execute(s -> {
				UUID id = jdbc.queryForObject(
						"INSERT INTO company (name, created_at, updated_at) VALUES ('Race', now(), now()) RETURNING id", UUID.class);
				seats[0] = jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
						+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now()) RETURNING id", UUID.class, id, owner.userId());
				seats[1] = jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
						+ "VALUES (?, ?, 'ADMIN', 'ACTIVE', now(), now()) RETURNING id", UUID.class, id, admin.userId());
				return id;
			});
			TransferView view = service.initiate(session(owner), companyId, seats[1]);
			List<String> statements = RunbookSql.statements(SCRIPT, Map.of("email", owner.email(), "ticket", "RACE-" + round));
			Callable<String> accept = () -> {
				try {
					service.accept(session(admin), companyId, view.id());
					return "ok";
				}
				catch (OwnershipConflictException e) {
					return "conflict";
				}
			};
			Callable<String> disable = () -> {
				tx.executeWithoutResult(s -> statements.forEach(jdbc::execute));
				return "ok";
			};

			List<String> results = Parallel.runAll(List.of(accept, disable));

			assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
					+ "AND status IN ('ACTIVE', 'SUSPENDED')", companyId)).as("round %d: exactly one Owner", round).isEqualTo(1);
			boolean flagged = jdbc.queryForObject("SELECT ownership_resolution_at IS NOT NULL FROM company WHERE id = ?",
					Boolean.class, companyId);
			if (results.get(0).equals("ok")) {
				assertThat(jdbc.queryForObject("SELECT role || ':' || status FROM membership WHERE id = ?", String.class, seats[1]))
					.isEqualTo("OWNER:ACTIVE");
				assertThat(flagged).as("the disabled account was only an Admin by then").isFalse();
				assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, view.id()))
					.isEqualTo("COMPLETED");
			}
			else {
				assertThat(results.get(0)).isEqualTo("conflict");
				assertThat(jdbc.queryForObject("SELECT role || ':' || status FROM membership WHERE id = ?", String.class, seats[0]))
					.isEqualTo("OWNER:SUSPENDED");
				assertThat(jdbc.queryForObject("SELECT role || ':' || status FROM membership WHERE id = ?", String.class, seats[1]))
					.isEqualTo("ADMIN:ACTIVE");
				assertThat(flagged).isTrue();
			}
		}
	}

}
