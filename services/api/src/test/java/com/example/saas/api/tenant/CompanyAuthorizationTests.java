package com.example.saas.api.tenant;

import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.TestEndpointsConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** The centralized check over HTTP, against PostgreSQL: 404 versus 403, and effect on the very next request. */
@Import({ TestEndpointsConfiguration.class, TenantTestEndpointsConfiguration.class })
class CompanyAuthorizationTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	private record Member(UUID userId, Cookie cookie) {
	}

	private Member member() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new Member(userId, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	/** The company and its Owner commit together, as the service will do it (the database requires an Owner). */
	private UUID company(Member owner) {
		return tx.execute(status -> {
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Acme', now(), now()) RETURNING id",
					UUID.class);
			join(companyId, owner, "OWNER", "ACTIVE");
			return companyId;
		});
	}

	private void join(UUID companyId, Member member, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, member.userId(), role, status);
	}

	private MvcResult call(Member caller, UUID companyId, String route) throws Exception {
		return api.get("/api/v1/companies/" + companyId + "/test/" + route, caller.cookie(), freshIp());
	}

	private int status(Member caller, UUID companyId, String route) throws Exception {
		return call(caller, companyId, route).getResponse().getStatus();
	}

	@Test
	void anActiveMemberWithThePermissionGetsTheCompanyAndTheRoleFromTheDatabase() throws Exception {
		Member owner = member();
		UUID companyId = company(owner);

		MvcResult result = call(owner, companyId, "manage-settings");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(AuthApi.parse(result).get("role").asText()).isEqualTo("OWNER");
	}

	@Test
	void aNonMemberAndAnUnknownCompanyGetTheSameNotFound() throws Exception {
		UUID companyId = company(member());
		Member stranger = member();

		MvcResult foreign = call(stranger, companyId, "view-company-details");
		MvcResult unknown = call(stranger, UUID.randomUUID(), "view-company-details");

		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
		assertThat(AuthApi.parse(foreign).get("type").asText()).isEqualTo("urn:saas:problem:not-found");
		assertThat(AuthApi.parse(foreign).get("detail").asText()).isEqualTo(AuthApi.parse(unknown).get("detail").asText());
	}

	@Test
	void aMalformedCompanyIdIsNotFoundToo() throws Exception {
		Member user = member();

		MvcResult result = api.get("/api/v1/companies/not-a-uuid/test/view", user.cookie(), freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void suspendedAndRemovedMembersGetNotFound() throws Exception {
		UUID companyId = company(member());
		Member suspended = member();
		Member removed = member();
		join(companyId, suspended, "AGENT", "SUSPENDED");
		join(companyId, removed, "AGENT", "REMOVED");

		assertThat(status(suspended, companyId, "view-company-details")).isEqualTo(404);
		assertThat(status(removed, companyId, "view-company-details")).isEqualTo(404);
	}

	@Test
	void anActiveMemberWithoutThePermissionGetsForbiddenAndTheDenialIsAudited() throws Exception {
		UUID companyId = company(member());
		Member viewer = member();
		join(companyId, viewer, "VIEWER", "ACTIVE");

		assertThat(status(viewer, companyId, "view-company-details")).isEqualTo(200);
		MvcResult denied = call(viewer, companyId, "edit-core-records");

		assertThat(denied.getResponse().getStatus()).isEqualTo(403);
		assertThat(AuthApi.parse(denied).get("type").asText()).isEqualTo("urn:saas:problem:forbidden");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' "
				+ "AND company_id = ? AND actor_id = ? AND outcome = 'denied' AND reason_code = 'edit_core_records'",
				companyId, viewer.userId())).isEqualTo(1);
	}

	@Test
	void probesForOtherTenantsAreNotAudited() throws Exception {
		Member stranger = member();

		status(stranger, UUID.randomUUID(), "view-company-details");

		assertThat(count("SELECT count(*) FROM audit_event WHERE actor_id = ? "
				+ "AND event_type = 'tenant.authorization.denied'", stranger.userId())).isZero();
	}

	@Test
	void aRoleChangeAppliesToTheNextRequestOnTheSameSession() throws Exception {
		UUID companyId = company(member());
		Member agent = member();
		join(companyId, agent, "AGENT", "ACTIVE");
		assertThat(status(agent, companyId, "edit-core-records")).isEqualTo(200);
		assertThat(status(agent, companyId, "manage-settings")).isEqualTo(403);

		jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ? AND user_id = ?", companyId,
				agent.userId());
		assertThat(status(agent, companyId, "manage-settings")).isEqualTo(200);

		jdbc.update("UPDATE membership SET role = 'VIEWER' WHERE company_id = ? AND user_id = ?", companyId,
				agent.userId());
		assertThat(status(agent, companyId, "edit-core-records")).isEqualTo(403);

		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE company_id = ? AND user_id = ?", companyId,
				agent.userId());
		assertThat(status(agent, companyId, "view-company-details")).isEqualTo(404);
	}

	@Test
	void oneCompanysSuspensionDoesNotAffectAnother() throws Exception {
		Member user = member();
		UUID first = company(member());
		UUID second = company(member());
		join(first, user, "AGENT", "ACTIVE");
		join(second, user, "AGENT", "ACTIVE");

		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE company_id = ? AND user_id = ?", first,
				user.userId());

		assertThat(status(user, first, "view-company-details")).isEqualTo(404);
		assertThat(status(user, second, "view-company-details")).isEqualTo(200);
	}

	@Test
	void whilePendingDeletionOnlyTheOwnerCanRead() throws Exception {
		Member owner = member();
		UUID companyId = company(owner);
		Member agent = member();
		join(companyId, agent, "AGENT", "ACTIVE");

		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", companyId);

		assertThat(status(owner, companyId, "view-company-details")).isEqualTo(200);
		assertThat(status(owner, companyId, "edit-core-records")).isEqualTo(403);
		assertThat(status(agent, companyId, "view-company-details")).isEqualTo(403);
	}

}
