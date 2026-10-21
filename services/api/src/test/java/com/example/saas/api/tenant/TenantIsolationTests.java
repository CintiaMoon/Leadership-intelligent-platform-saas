package com.example.saas.api.tenant;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.TestEndpointsConfiguration;
import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.testrecords.TestNoteRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant isolation and the whole role-permission matrix over HTTP, against PostgreSQL (AC-TEN-03, 04, 08, 09, 10).
 * One route per permission exists in the test source tree ({@link PermissionRoutesController}).
 */
@Import({ TestEndpointsConfiguration.class, TenantTestEndpointsConfiguration.class })
class TenantIsolationTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private TestNoteRepository notes;

	private record Member(UUID userId, Cookie cookie) {
	}

	private Member member() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new Member(userId, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	private UUID company(Member owner) {
		return tx.execute(s -> {
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Tenant', now(), now()) RETURNING id",
					UUID.class);
			join(companyId, owner, "OWNER", "ACTIVE");
			return companyId;
		});
	}

	private void join(UUID companyId, Member member, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, member.userId(), role, status);
	}

	private static String route(Permission permission) {
		return permission.name().toLowerCase().replace('_', '-');
	}

	private int get(Member caller, UUID companyId, String route) throws Exception {
		return api.get("/api/v1/companies/" + companyId + "/test/" + route, caller.cookie(), freshIp())
			.getResponse()
			.getStatus();
	}

	private UUID record(UUID companyId, String name) {
		return jdbc.queryForObject("INSERT INTO test_record (company_id, name) VALUES (?, ?) RETURNING id", UUID.class,
				companyId, name);
	}

	/** One company with one ACTIVE member per role. */
	private record Team(UUID companyId, Map<Role, Member> byRole) {
	}

	private Team team() throws Exception {
		Map<Role, Member> byRole = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			byRole.put(role, member());
		}
		UUID companyId = company(byRole.get(Role.OWNER));
		for (Role role : Role.values()) {
			if (role != Role.OWNER) {
				join(companyId, byRole.get(role), role.name(), "ACTIVE");
			}
		}
		return new Team(companyId, byRole);
	}

	@Test
	void everyPermissionForEveryRoleIsEnforcedOverHttp() throws Exception {
		Team team = team();

		for (Permission permission : Permission.values()) {
			for (Role role : Role.values()) {
				int expected = CompanyPolicy.permits(role, permission, CompanyStatus.ACTIVE, null) ? 200 : 403;
				assertThat(get(team.byRole().get(role), team.companyId(), route(permission)))
					.as("%s / %s", permission, role)
					.isEqualTo(expected);
			}
		}
	}

	private int targeted(Member caller, UUID companyId, Permission permission, Role target) throws Exception {
		return api.get("/api/v1/companies/" + companyId + "/test/targeted/" + permission.name() + "?targetRole=" + target,
				caller.cookie(), freshIp()).getResponse().getStatus();
	}

	/** Literal expectations, written from the spec's "Agent/Viewer only" wording, not derived from the policy. */
	@Test
	void anAdminActsOnAgentsAndViewersOnlyAndTheOwnerOnAnyoneOverHttp() throws Exception {
		Team team = team();
		Member owner = team.byRole().get(Role.OWNER);
		Member admin = team.byRole().get(Role.ADMIN);
		for (Permission permission : new Permission[] { Permission.CHANGE_MEMBER_ROLE,
				Permission.SUSPEND_OR_REMOVE_MEMBERS, Permission.REACTIVATE_MEMBERS }) {
			assertThat(targeted(admin, team.companyId(), permission, Role.AGENT)).as("%s Agent", permission).isEqualTo(200);
			assertThat(targeted(admin, team.companyId(), permission, Role.VIEWER)).as("%s Viewer", permission).isEqualTo(200);
			assertThat(targeted(admin, team.companyId(), permission, Role.ADMIN)).as("%s Admin", permission).isEqualTo(403);
			assertThat(targeted(admin, team.companyId(), permission, Role.OWNER)).as("%s Owner", permission).isEqualTo(403);
			for (Role target : Role.values()) {
				assertThat(targeted(owner, team.companyId(), permission, target)).as("Owner %s %s", permission, target)
					.isEqualTo(200);
				assertThat(targeted(team.byRole().get(Role.AGENT), team.companyId(), permission, target))
					.as("Agent %s %s", permission, target)
					.isEqualTo(403);
				assertThat(targeted(team.byRole().get(Role.VIEWER), team.companyId(), permission, target))
					.as("Viewer %s %s", permission, target)
					.isEqualTo(403);
			}
		}
		// An Admin never assigns or revokes the Admin role, whatever the target.
		assertThat(targeted(admin, team.companyId(), Permission.ASSIGN_OR_REVOKE_ADMIN, Role.AGENT)).isEqualTo(403);
	}

	@Test
	void aNonCanonicalUuidFormNeverReachesACompany() throws Exception {
		Member owner = member();
		UUID companyId = UUID.fromString("0000000a-0000-0000-0000-00000000000a");
		tx.executeWithoutResult(s -> {
			jdbc.update("INSERT INTO company (id, name, created_at, updated_at) VALUES (?, 'Short', now(), now())",
					companyId);
			join(companyId, owner, "OWNER", "ACTIVE");
		});

		assertThat(get(owner, companyId, route(Permission.VIEW_COMPANY_DETAILS))).isEqualTo(200);
		// UUID.fromString would read "a-0-0-0-a" as the same company; the path must be the canonical form.
		MvcResult shortForm = api.get("/api/v1/companies/a-0-0-0-a/test/" + route(Permission.VIEW_COMPANY_DETAILS),
				owner.cookie(), freshIp());
		assertThat(shortForm.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void whilePendingDeletionOnlyTheOwnerReadsAndNobodyWritesExceptTheCancellation() throws Exception {
		Team team = team();
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", team.companyId());

		for (Permission permission : Permission.values()) {
			for (Role role : Role.values()) {
				int expected = CompanyPolicy.permits(role, permission, CompanyStatus.PENDING_DELETION, null) ? 200
						: 403;
				assertThat(get(team.byRole().get(role), team.companyId(), route(permission)))
					.as("%s / %s while pending deletion", permission, role)
					.isEqualTo(expected);
			}
		}
		assertThat(get(team.byRole().get(Role.OWNER), team.companyId(), route(Permission.CANCEL_COMPANY_DELETION)))
			.isEqualTo(200);
		assertThat(get(team.byRole().get(Role.OWNER), team.companyId(), route(Permission.EDIT_CORE_RECORDS)))
			.isEqualTo(403);
	}

	@Test
	void aUserWithoutActiveMembershipGetsNotFoundOnEveryRoute() throws Exception {
		Team other = team();
		Member outsider = member();
		Member suspended = member();
		Member removed = member();
		join(other.companyId(), suspended, "ADMIN", "SUSPENDED");
		join(other.companyId(), removed, "ADMIN", "REMOVED");

		for (Permission permission : Permission.values()) {
			for (Member caller : new Member[] { outsider, suspended, removed }) {
				assertThat(get(caller, other.companyId(), route(permission))).as(permission.name()).isEqualTo(404);
			}
		}
	}

	@Test
	void swappingTheCompanyIdNeverGrantsAnotherTenantsData() throws Exception {
		Team mine = team();
		Team theirs = team();
		Member me = mine.byRole().get(Role.OWNER);
		UUID theirRecord = record(theirs.companyId(), "Secret");

		for (Permission permission : Permission.values()) {
			assertThat(get(me, mine.companyId(), route(permission)))
				.as("own company %s", permission)
				.isEqualTo(CompanyPolicy.permits(Role.OWNER, permission, CompanyStatus.ACTIVE, null) ? 200 : 403);
			assertThat(get(me, theirs.companyId(), route(permission))).as("their company %s", permission)
				.isEqualTo(404);
		}
		// A record ID from another company, asked for under my own company, looks like a missing record.
		MvcResult viaMine = api.get("/api/v1/companies/" + mine.companyId() + "/test/records/" + theirRecord,
				me.cookie(), freshIp());
		assertThat(viaMine.getResponse().getStatus()).isEqualTo(404);
		assertThat(viaMine.getResponse().getContentAsString()).doesNotContain("Secret");
	}

	@Test
	void aRoleInOneCompanyGivesNothingInAnother() throws Exception {
		Team a = team();
		Team b = team();
		Member user = a.byRole().get(Role.OWNER);
		join(b.companyId(), user, "VIEWER", "ACTIVE");

		assertThat(get(user, a.companyId(), route(Permission.MANAGE_SETTINGS))).isEqualTo(200);
		assertThat(get(user, b.companyId(), route(Permission.MANAGE_SETTINGS))).isEqualTo(403);
		assertThat(get(user, b.companyId(), route(Permission.VIEW_CORE_RECORDS))).isEqualTo(200);
	}

	@Test
	void suspendingOrRemovingAMembershipInOneCompanyLeavesTheOthersUntouched() throws Exception {
		Team a = team();
		Team b = team();
		Member user = member();
		join(a.companyId(), user, "ADMIN", "ACTIVE");
		join(b.companyId(), user, "AGENT", "ACTIVE");

		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE company_id = ? AND user_id = ?", a.companyId(),
				user.userId());
		for (Permission permission : Permission.values()) {
			assertThat(get(user, a.companyId(), route(permission))).as("suspended in A: %s", permission).isEqualTo(404);
			assertThat(get(user, b.companyId(), route(permission))).as("B unaffected: %s", permission)
				.isEqualTo(CompanyPolicy.permits(Role.AGENT, permission, CompanyStatus.ACTIVE, null) ? 200 : 403);
		}

		jdbc.update("UPDATE membership SET status = 'REMOVED' WHERE company_id = ? AND user_id = ?", b.companyId(),
				user.userId());
		assertThat(get(user, b.companyId(), route(Permission.VIEW_COMPANY_DETAILS))).isEqualTo(404);
		assertThat(get(user, a.companyId(), route(Permission.VIEW_COMPANY_DETAILS))).isEqualTo(404);
		jdbc.update("UPDATE membership SET status = 'ACTIVE' WHERE company_id = ? AND user_id = ? AND role = 'ADMIN'",
				a.companyId(), user.userId());
		assertThat(get(user, a.companyId(), route(Permission.VIEW_COMPANY_DETAILS))).isEqualTo(200);
	}

	@Test
	void aRelatedRecordFromAnotherCompanyIsRejectedAndNothingChanges() throws Exception {
		Team mine = team();
		Team theirs = team();
		Member me = mine.byRole().get(Role.OWNER);
		UUID myRecord = record(mine.companyId(), "Mine");
		UUID theirRecord = record(theirs.companyId(), "Theirs");
		String ip = freshIp();
		String csrf = api.sessionCsrf(me.cookie(), ip);
		String path = "/api/v1/companies/" + mine.companyId() + "/test/notes";
		int before = count("SELECT count(*) FROM test_note");

		MvcResult foreign = api.postWithSession(path, "{\"recordId\":\"" + theirRecord + "\",\"body\":\"x\"}",
				me.cookie(), csrf, ip);
		MvcResult unknown = api.postWithSession(path, "{\"recordId\":\"" + UUID.randomUUID() + "\",\"body\":\"x\"}",
				me.cookie(), csrf, ip);

		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
		assertThat(AuthApi.parse(foreign).get("detail").asString()).isEqualTo(AuthApi.parse(unknown).get("detail").asString());
		assertThat(count("SELECT count(*) FROM test_note")).isEqualTo(before);

		MvcResult own = api.postWithSession(path, "{\"recordId\":\"" + myRecord + "\",\"body\":\"ok\"}", me.cookie(),
				csrf, ip);
		assertThat(own.getResponse().getStatus()).isEqualTo(201);
		assertThat(count("SELECT count(*) FROM test_note WHERE company_id = ?", mine.companyId())).isEqualTo(1);
	}

	@Test
	void theDatabaseAlsoRefusesANoteThatPointsAtAnotherCompanysRecord() throws Exception {
		Team mine = team();
		Team theirs = team();
		UUID theirRecord = record(theirs.companyId(), "Theirs");

		assertThatThrownBy(() -> jdbc.update("INSERT INTO test_note (company_id, record_id, body) VALUES (?, ?, 'x')",
				mine.companyId(), theirRecord))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aViewerCannotWriteARecordAndTheDenialLeavesNothing() throws Exception {
		Team team = team();
		Member viewer = team.byRole().get(Role.VIEWER);
		UUID recordId = record(team.companyId(), "R");
		String ip = freshIp();
		int before = count("SELECT count(*) FROM test_note");

		MvcResult result = api.postWithSession("/api/v1/companies/" + team.companyId() + "/test/notes",
				json("recordId", recordId.toString(), "body", "x"), viewer.cookie(),
				api.sessionCsrf(viewer.cookie(), ip), ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM test_note")).isEqualTo(before);
		assertThat(notes.findByCompanyId(team.companyId())).isEmpty();
	}

}
