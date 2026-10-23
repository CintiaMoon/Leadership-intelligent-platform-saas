package com.example.saas.api.tenant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.TestEndpointsConfiguration;
import com.example.saas.api.tenant.domain.Permission;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-MEM-14 and AC-MEM-18 (D-54): runs the operator's SQL from the runbook against PostgreSQL. A company whose Owner's
 * account is disabled keeps exactly one Owner membership, suspended; it is flagged; Owner-only operations and company
 * deletion are unavailable; everyone else keeps working.
 */
@Import({ TestEndpointsConfiguration.class, TenantTestEndpointsConfiguration.class })
class OwnerAccountDisableProcedureTests extends AuthIntegrationTest {

	private static final Path SCRIPT = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"disable-account-and-flag-companies.sql");

	@Autowired
	private TransactionTemplate tx;

	private record Person(String email, UUID userId, Cookie cookie, String csrf) {
	}

	private Person person() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		return new Person(email, userId, cookie, api.sessionCsrf(cookie, freshIp()));
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
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Flag', now(), now()) RETURNING id",
					UUID.class);
			return new Company(id, seat(id, owner.userId(), "OWNER"));
		});
	}

	/** The runbook's statements with the psql variable replaced; comments dropped; one statement per element. */
	private static List<String> statements(String email, String ticket) throws IOException {
		StringBuilder current = new StringBuilder();
		List<String> out = new ArrayList<>();
		for (String line : Files.readAllLines(SCRIPT)) {
			if (line.strip().startsWith("--")) {
				continue;
			}
			current.append(line.replace(":'email'", "'" + email.replace("'", "''") + "'")
				.replace(":'ticket'", "'" + ticket.replace("'", "''") + "'")).append('\n');
			if (line.stripTrailing().endsWith(";")) {
				out.add(current.toString());
				current.setLength(0);
			}
		}
		assertThat(current.toString().strip()).as("the script ends with a complete statement").isEmpty();
		return out;
	}

	/** Runs the script in one transaction, as psql --single-transaction does. */
	private void runProcedure(String email) throws IOException {
		runProcedure(email, "TEST-1");
	}

	private void runProcedure(String email, String ticket) throws IOException {
		List<String> statements = statements(email, ticket);
		assertThat(statements).as("the eight statements of the runbook").hasSize(8);
		tx.executeWithoutResult(s -> statements.forEach(jdbc::execute));
	}

	private int request(Person who, HttpMethod method, String path, String body) throws Exception {
		return api.send(method, path, body, who.cookie(), who.csrf(), freshIp()).getResponse().getStatus();
	}

	@Test
	void disablingAnOwnersAccountSuspendsItsOwnerMembershipsFlagsTheCompaniesAndKeepsOneOwnerEach() throws Exception {
		Person owner = person();
		Person admin = person();
		Person agent = person();
		Person viewer = person();
		Person otherOwner = person();
		Company first = company(owner);
		Company second = company(owner);
		Company third = company(otherOwner);
		UUID adminSeat = tx.execute(s -> seat(first.id(), admin.userId(), "ADMIN"));
		UUID agentSeat = tx.execute(s -> seat(first.id(), agent.userId(), "AGENT"));
		tx.executeWithoutResult(s -> {
			seat(first.id(), viewer.userId(), "VIEWER");
			seat(third.id(), owner.userId(), "AGENT");
		});
		assertThat(api.get("/api/v1/auth/session", owner.cookie(), freshIp()).getResponse().getStatus()).isEqualTo(200);

		runProcedure(owner.email());

		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.isEqualTo("DISABLED");
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", owner.userId().toString()))
			.as("the sessions are gone").isZero();
		assertThat(api.get("/api/v1/auth/session", owner.cookie(), freshIp()).getResponse().getStatus())
			.isEqualTo(401);
		for (Company flagged : List.of(first, second)) {
			assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class,
					flagged.ownerSeat())).as("suspended, not removed").isEqualTo("SUSPENDED");
			assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class,
					flagged.ownerSeat())).as("still the Owner").isEqualTo("OWNER");
			assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
					+ "AND status IN ('ACTIVE', 'SUSPENDED')", flagged.id())).as("exactly one Owner").isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT ownership_resolution_at IS NOT NULL FROM company WHERE id = ?",
					Boolean.class, flagged.id())).isTrue();
			assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_type = 'operator' "
					+ "AND event_type IN ('membership.suspended', 'company.ownership_resolution.flagged')",
					flagged.id())).isEqualTo(2);
		}
		assertThat(jdbc.queryForObject("SELECT ownership_resolution_at IS NULL FROM company WHERE id = ?",
				Boolean.class, third.id())).as("a company the account only belongs to is not flagged").isTrue();
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE company_id = ? AND user_id = ?",
				String.class, third.id(), owner.userId())).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, adminSeat))
			.as("other members are untouched").isEqualTo("ACTIVE");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'account.status.changed' "
				+ "AND target_id = ? AND actor_type = 'operator' AND before_value = 'ACTIVE' "
				+ "AND after_value = 'DISABLED' AND reason_code LIKE 'operator_disable ticket=TEST-1 db_user=%'",
				owner.userId())).as("the audit row names the ticket and the database role").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? "
				+ "AND event_type IN ('membership.suspended', 'company.ownership_resolution.flagged') "
				+ "AND reason_code = 'owner_account_disabled ticket=TEST-1'", first.id())).isEqualTo(2);

		// The company keeps working for everyone else, but nothing that needs an Owner is available.
		String base = "/api/v1/companies/" + first.id();
		assertThat(api.get(base, agent.cookie(), freshIp()).getResponse().getStatus()).isEqualTo(200);
		assertThat(api.get(base + "/members", admin.cookie(), freshIp()).getResponse().getStatus()).isEqualTo(200);
		assertThat(request(admin, HttpMethod.PATCH, base + "/members/" + agentSeat, json("role", "ADMIN")))
			.as("only an Owner promotes to Admin").isEqualTo(403);
		for (HttpMethod method : new HttpMethod[] { HttpMethod.PATCH, HttpMethod.DELETE }) {
			assertThat(request(admin, method, base + "/members/" + first.ownerSeat(),
					method == HttpMethod.PATCH ? json("role", "AGENT") : null)).as("an Admin cannot touch the Owner")
				.isEqualTo(403);
		}
		assertThat(request(admin, HttpMethod.POST, base + "/members/" + first.ownerSeat() + "/reactivate", null))
			.isEqualTo(403);
		for (Person member : List.of(admin, agent, viewer)) {
			for (Permission ownerOnly : new Permission[] { Permission.REQUEST_COMPANY_DELETION,
					Permission.CANCEL_COMPANY_DELETION, Permission.TRANSFER_OWNERSHIP,
					Permission.ASSIGN_OR_REVOKE_ADMIN, Permission.MANAGE_PLANS }) {
				assertThat(api.get(base + "/test/" + ownerOnly.name().toLowerCase().replace('_', '-'), member.cookie(),
						freshIp()).getResponse().getStatus()).as("%s", ownerOnly).isEqualTo(403);
			}
		}
	}

	@Test
	void runningTheProcedureAgainChangesNothing() throws Exception {
		Person owner = person();
		Company company = company(owner);
		runProcedure(owner.email());
		int audit = count("SELECT count(*) FROM audit_event WHERE company_id = ? OR target_id = ?", company.id(),
				owner.userId());
		Object flaggedAt = jdbc.queryForObject("SELECT ownership_resolution_at FROM company WHERE id = ?",
				java.sql.Timestamp.class, company.id());

		runProcedure(owner.email());

		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? OR target_id = ?", company.id(),
				owner.userId())).as("no second set of audit rows").isEqualTo(audit);
		assertThat(jdbc.queryForObject("SELECT ownership_resolution_at FROM company WHERE id = ?",
				java.sql.Timestamp.class, company.id())).as("the flag time is kept").isEqualTo(flaggedAt);
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, company.ownerSeat()))
			.isEqualTo("SUSPENDED");
	}

	@Test
	void anUnknownAddressStopsTheScriptAndChangesNothing() throws Exception {
		int audit = count("SELECT count(*) FROM audit_event");
		int suspended = count("SELECT count(*) FROM membership WHERE status = 'SUSPENDED'");

		assertThatThrownBy(() -> runProcedure("nobody." + UUID.randomUUID() + "@example.com"))
			.as("a typo must not look like success")
			.isInstanceOf(DataAccessException.class);

		assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(audit);
		assertThat(count("SELECT count(*) FROM membership WHERE status = 'SUSPENDED'")).isEqualTo(suspended);
	}

	@Test
	void aMissingOrMalformedTicketStopsTheScriptAndChangesNothing() throws Exception {
		Person owner = person();
		Company company = company(owner);

		for (String ticket : new String[] { "", "has space", "semi;colon", "x".repeat(41), ":'ticket'" }) {
			assertThatThrownBy(() -> runProcedure(owner.email(), ticket)).as("ticket '%s'", ticket)
				.isInstanceOf(DataAccessException.class);
		}

		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, company.ownerSeat()))
			.isEqualTo("ACTIVE");
	}

	@Test
	void theAddressIsTrimmedAndLowerCased() throws Exception {
		Person owner = person();
		Company company = company(owner);

		runProcedure("  " + owner.email().toUpperCase() + "  ");

		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, owner.userId()))
			.isEqualTo("DISABLED");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, company.ownerSeat()))
			.isEqualTo("SUSPENDED");
	}

	/** The dead end the runbook warns about (D-142): re-enabling the account does not bring the Owner back. */
	@Test
	void aReEnabledOwnerStaysSuspendedAndNobodyCanReactivateTheMembership() throws Exception {
		Person owner = person();
		Person admin = person();
		Company company = company(owner);
		UUID adminSeat = tx.execute(s -> seat(company.id(), admin.userId(), "ADMIN"));
		runProcedure(owner.email());

		jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", owner.userId());
		Cookie again = sessionCookie(api.login(owner.email(), STRONG_PASSWORD, freshIp()));
		String base = "/api/v1/companies/" + company.id();

		assertThat(api.get("/api/v1/auth/session", again, freshIp()).getResponse().getStatus())
			.as("the account works again").isEqualTo(200);
		assertThat(api.get(base, again, freshIp()).getResponse().getStatus())
			.as("but the suspended Owner membership gives no access to the company").isEqualTo(404);
		assertThat(request(admin, HttpMethod.POST, base + "/members/" + company.ownerSeat() + "/reactivate", null))
			.as("an Admin may not act on an Owner").isEqualTo(403);
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, company.ownerSeat()))
			.isEqualTo("SUSPENDED");
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
				+ "AND status IN ('ACTIVE', 'SUSPENDED')", company.id())).isEqualTo(1);
		assertThat(adminSeat).isNotNull();
	}

	@Test
	void anAccountThatOwnsNothingIsOnlyDisabled() throws Exception {
		Person agent = person();
		Person owner = person();
		Company company = company(owner);
		UUID seat = tx.execute(s -> seat(company.id(), agent.userId(), "AGENT"));

		runProcedure(agent.email());

		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, agent.userId()))
			.isEqualTo("DISABLED");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, seat))
			.as("only Owner memberships are suspended by the procedure").isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT ownership_resolution_at IS NULL FROM company WHERE id = ?",
				Boolean.class, company.id())).isTrue();
		assertThat(api.get("/api/v1/companies/" + company.id(), agent.cookie(), freshIp()).getResponse().getStatus())
			.as("a disabled account is rejected everywhere").isEqualTo(401);
	}

}
