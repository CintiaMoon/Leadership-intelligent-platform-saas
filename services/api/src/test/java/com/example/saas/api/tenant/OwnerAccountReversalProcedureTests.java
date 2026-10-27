package com.example.saas.api.tenant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.application.CompanyLocks;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-142: a mistaken disable can be undone with a reviewed script that restores the Owner membership and clears the flag.
 * Runs the runbook's reversal SQL against PostgreSQL, after the disable SQL.
 */
class OwnerAccountReversalProcedureTests extends AuthIntegrationTest {

	private static final Path DISABLE = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"disable-account-and-flag-companies.sql");

	private static final Path REVERSE = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"reverse-account-disable.sql");

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private CompanyLocks locks;

	private record Person(String email, UUID userId) {
	}

	private Person person() throws Exception {
		String email = AuthApi.uniqueEmail();
		return new Person(email, registerVerified(email, STRONG_PASSWORD));
	}

	private UUID seat(UUID companyId, UUID userId, String role) {
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'ACTIVE', now(), now()) RETURNING id", UUID.class, companyId, userId, role);
	}

	private record Company(UUID id, UUID ownerSeat) {
	}

	private Company company(Person owner) {
		return tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Reverse', now(), now()) RETURNING id",
					UUID.class);
			return new Company(id, seat(id, owner.userId(), "OWNER"));
		});
	}

	private void run(Path script, String email, String ticket, int expectedStatements) throws IOException {
		List<String> statements = RunbookSql.statements(script, Map.of("email", email, "ticket", ticket));
		assertThat(statements).as("the statements of %s", script.getFileName()).hasSize(expectedStatements);
		tx.executeWithoutResult(s -> statements.forEach(jdbc::execute));
	}

	private void disable(String email) throws IOException {
		run(DISABLE, email, "SEC-1", 11);
	}

	private void reverse(String email) throws IOException {
		run(REVERSE, email, "SEC-2", 10);
	}

	private String membershipStatus(UUID seat) {
		return jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, seat);
	}

	private boolean flagged(UUID companyId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT ownership_resolution_at IS NOT NULL FROM company WHERE id = ?", Boolean.class, companyId));
	}

	private int liveOwners(UUID companyId) {
		return count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
				+ "AND status IN ('ACTIVE', 'SUSPENDED')", companyId);
	}

	@Test
	void aMistakenDisableIsUndoneOwnerRestoredFlagClearedAccountEnabledAndTheOwnerCanWorkAgain() throws Exception {
		Person owner = person();
		Person other = person();
		Company first = company(owner);
		Company second = company(owner);
		Company third = company(other);
		UUID memberSeat = tx.execute(s -> seat(third.id(), owner.userId(), "AGENT"));
		disable(owner.email());
		assertThat(membershipStatus(first.ownerSeat())).isEqualTo("SUSPENDED");
		assertThat(flagged(first.id())).isTrue();

		reverse(owner.email());

		for (Company company : List.of(first, second)) {
			assertThat(membershipStatus(company.ownerSeat())).isEqualTo("ACTIVE");
			assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, company.ownerSeat()))
				.isEqualTo("OWNER");
			assertThat(flagged(company.id())).as("flag cleared").isFalse();
			assertThat(liveOwners(company.id())).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_type = 'operator' "
					+ "AND ((event_type = 'membership.reactivated' AND before_value = 'SUSPENDED' AND after_value = 'ACTIVE') "
					+ "OR event_type = 'company.ownership_resolution.cleared') "
					+ "AND reason_code LIKE 'owner_account_restored ticket=SEC-2%'", company.id())).isEqualTo(2);
		}
		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.isEqualTo("ACTIVE");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'account.status.changed' "
				+ "AND target_id = ? AND before_value = 'DISABLED' AND after_value = 'ACTIVE' "
				+ "AND reason_code LIKE 'operator_enable ticket=SEC-2 db_user=%'", owner.userId())).isEqualTo(1);
		assertThat(membershipStatus(memberSeat)).as("memberships elsewhere were never touched (D-141)")
			.isEqualTo("ACTIVE");
		assertThat(flagged(third.id())).isFalse();

		Cookie cookie = sessionCookie(api.login(owner.email(), STRONG_PASSWORD, freshIp()));
		assertThat(api.get("/api/v1/companies/" + first.id(), cookie, freshIp()).getResponse().getStatus())
			.as("the Owner works again after logging in").isEqualTo(200);
		assertThat(api.get("/api/v1/companies/" + second.id() + "/members", cookie, freshIp()).getResponse().getStatus())
			.isEqualTo(200);
	}

	@Test
	void runningTheReversalAgainChangesNothing() throws Exception {
		Person owner = person();
		Company company = company(owner);
		disable(owner.email());
		reverse(owner.email());
		int audit = count("SELECT count(*) FROM audit_event WHERE company_id = ? OR target_id = ?", company.id(),
				owner.userId());

		reverse(owner.email());

		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? OR target_id = ?", company.id(),
				owner.userId())).isEqualTo(audit);
		assertThat(membershipStatus(company.ownerSeat())).isEqualTo("ACTIVE");
	}

	@Test
	void afterAnOwnershipTransferTheReversalRestoresNothingAndTheCompanyStaysFlagged() throws Exception {
		Person owner = person();
		Person successor = person();
		Company company = company(owner);
		UUID successorSeat = tx.execute(s -> seat(company.id(), successor.userId(), "ADMIN"));
		disable(owner.email());
		// What slice 005 will do: lock the company row first, demote the old Owner, promote the successor, commit.
		tx.executeWithoutResult(s -> {
			locks.lockCompany(company.id());
			jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE id = ?", company.ownerSeat());
			jdbc.update("UPDATE membership SET role = 'OWNER' WHERE id = ?", successorSeat);
		});

		reverse(owner.email());

		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, successorSeat))
			.as("the successor stays the Owner").isEqualTo("OWNER");
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, company.ownerSeat()))
			.isEqualTo("ADMIN");
		assertThat(membershipStatus(company.ownerSeat())).as("the old Owner is not reactivated").isEqualTo("SUSPENDED");
		assertThat(flagged(company.id())).as("the script does not clear a flag it did not resolve").isTrue();
		assertThat(liveOwners(company.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.as("the account itself is enabled again").isEqualTo("ACTIVE");
	}

	@Test
	void anOwnerSuspendedByAnythingOtherThanTheDisableScriptIsNeverRestored() throws Exception {
		Person owner = person();
		Company company = company(owner);
		// Suspended and flagged by hand, with no operator suspension in the audit trail for this membership.
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", company.ownerSeat());
		jdbc.update("UPDATE company SET ownership_resolution_at = now() WHERE id = ?", company.id());

		reverse(owner.email());

		assertThat(membershipStatus(company.ownerSeat())).isEqualTo("SUSPENDED");
		assertThat(flagged(company.id())).isTrue();
	}

	@Test
	void anUnknownAddressOrABadTicketStopsTheScriptAndChangesNothing() throws Exception {
		Person owner = person();
		Company company = company(owner);
		disable(owner.email());
		int audit = count("SELECT count(*) FROM audit_event");

		assertThatThrownBy(() -> reverse("nobody." + UUID.randomUUID() + "@example.com"))
			.isInstanceOf(DataAccessException.class);
		for (String ticket : new String[] { "", "has space", "x".repeat(41) }) {
			assertThatThrownBy(() -> run(REVERSE, owner.email(), ticket, 10)).as("ticket '%s'", ticket)
				.isInstanceOf(DataAccessException.class);
		}

		assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(audit);
		assertThat(membershipStatus(company.ownerSeat())).isEqualTo("SUSPENDED");
		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.isEqualTo("DISABLED");
	}

	@Test
	void runOutsideASingleTransactionTheReversalStopsBeforeItChangesAnything() throws Exception {
		Person owner = person();
		Company company = company(owner);
		disable(owner.email());
		List<String> statements = RunbookSql.statements(REVERSE, Map.of("email", owner.email(), "ticket", "SEC-2"));

		// Each statement in its own transaction, as psql does without --single-transaction.
		assertThatThrownBy(() -> statements.forEach(jdbc::execute)).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("division by zero");

		assertThat(membershipStatus(company.ownerSeat())).isEqualTo("SUSPENDED");
		assertThat(flagged(company.id())).isTrue();
		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.isEqualTo("DISABLED");
	}

	@Test
	void anAccountThatOwnsNothingIsOnlyEnabled() throws Exception {
		Person agent = person();
		Person owner = person();
		Company company = company(owner);
		UUID seat = tx.execute(s -> seat(company.id(), agent.userId(), "AGENT"));
		disable(agent.email());

		reverse(agent.email());

		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, agent.userId()))
			.isEqualTo("ACTIVE");
		assertThat(membershipStatus(seat)).isEqualTo("ACTIVE");
		assertThat(membershipStatus(company.ownerSeat())).isEqualTo("ACTIVE");
		assertThat(flagged(company.id())).isFalse();
	}

}
