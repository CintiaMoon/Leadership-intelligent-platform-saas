package com.example.saas.api.tenant;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.saas.api.MutableClock;
import com.example.saas.api.MutableClockConfiguration;
import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.TestEndpointsConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.parse;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** The verdicts of the membership-change authorizer as the API reports them: 200, 400, 403, 404, 409. */
@Import({ MutableClockConfiguration.class, TestEndpointsConfiguration.class, TenantTestEndpointsConfiguration.class })
class MembershipChangeHttpTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private MutableClock clock;

	private record Member(UUID userId, UUID membershipId, Cookie cookie) {
	}

	@BeforeEach
	void resetClock() {
		clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	private Member member(UUID companyId, String role, String status) throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		UUID membershipId = companyId == null ? null
				: jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, "
						+ "updated_at) VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, userId,
						role, status);
		return new Member(userId, membershipId, cookie);
	}

	private UUID company(String ownerEmailSeed) throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID ownerId = registerVerified(email, STRONG_PASSWORD);
		return tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Http', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, ownerId);
			return id;
		});
	}

	private MvcResult call(Member caller, UUID companyId, String operation, UUID membershipId, String newRole)
			throws Exception {
		String query = (membershipId == null ? "" : "membershipId=" + membershipId)
				+ (newRole == null ? "" : (membershipId == null ? "" : "&") + "newRole=" + newRole);
		return api.get("/api/v1/companies/" + companyId + "/test/authorize-change/" + operation
				+ (query.isEmpty() ? "" : "?" + query), caller.cookie(), freshIp());
	}

	private UUID ownerMembership(UUID companyId) {
		return jdbc.queryForObject("SELECT id FROM membership WHERE company_id = ? AND role = 'OWNER'", UUID.class,
				companyId);
	}

	@Test
	void anAllowedChangeIsAnsweredWith200() throws Exception {
		UUID companyId = company("a");
		Member admin = member(companyId, "ADMIN", "ACTIVE");
		Member agent = member(companyId, "AGENT", "ACTIVE");

		MvcResult result = call(admin, companyId, "SUSPEND", agent.membershipId(), null);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(parse(result).get("actorRole").asString()).isEqualTo("ADMIN");
		assertThat(parse(result).get("targetRole").asString()).isEqualTo("AGENT");
	}

	@Test
	void aStrangerAndAForeignMembershipGetNotFound() throws Exception {
		UUID mine = company("a");
		UUID theirs = company("b");
		Member agent = member(theirs, "AGENT", "ACTIVE");
		Member stranger = member(null, null, null);
		Member admin = member(mine, "ADMIN", "ACTIVE");

		MvcResult outsider = call(stranger, mine, "SUSPEND", ownerMembership(mine), null);
		MvcResult foreign = call(admin, mine, "SUSPEND", agent.membershipId(), null);

		assertThat(outsider.getResponse().getStatus()).isEqualTo(404);
		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(parse(outsider).get("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(parse(foreign).get("detail").asString()).isEqualTo(parse(outsider).get("detail").asString());
	}

	@Test
	void aRefusedRoleGetsForbiddenAndAnImpossibleChangeGetsConflict() throws Exception {
		UUID companyId = company("a");
		Member viewer = member(companyId, "VIEWER", "ACTIVE");
		Member admin = member(companyId, "ADMIN", "ACTIVE");
		Member suspended = member(companyId, "AGENT", "SUSPENDED");

		MvcResult forbidden = call(viewer, companyId, "SUSPEND", suspended.membershipId(), null);
		MvcResult adminOnOwner = call(admin, companyId, "REMOVE", ownerMembership(companyId), null);
		MvcResult conflict = call(admin, companyId, "SUSPEND", suspended.membershipId(), null);

		assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
		assertThat(parse(forbidden).get("code").asString()).isEqualTo("FORBIDDEN");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' "
				+ "AND company_id = ? AND actor_id = ? AND reason_code = 'suspend_or_remove_members'", companyId,
				viewer.userId())).as("the handler audited the denial once the transaction had ended").isEqualTo(1);
		assertThat(adminOnOwner.getResponse().getStatus()).isEqualTo(403);
		assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
		assertThat(parse(conflict).get("code").asString()).isEqualTo("CONFLICT");
		assertThat(conflict.getResponse().getContentType()).startsWith("application/problem+json");
	}

	@Test
	void assigningOwnerIsABadRequestWithAFieldError() throws Exception {
		UUID companyId = company("a");
		Member owner = member(null, null, null);
		jdbc.update("UPDATE membership SET user_id = ? WHERE company_id = ? AND role = 'OWNER'", owner.userId(),
				companyId);
		Member agent = member(companyId, "AGENT", "ACTIVE");

		MvcResult result = call(owner, companyId, "CHANGE_ROLE", agent.membershipId(), "OWNER");

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(parse(result).get("errors").get(0).get("field").asString()).isEqualTo("role");
	}

	@Test
	void promotingToAdminNeedsARecentLoginButOtherRoleChangesDoNot() throws Exception {
		UUID companyId = company("a");
		Member owner = member(null, null, null);
		jdbc.update("UPDATE membership SET user_id = ? WHERE company_id = ? AND role = 'OWNER'", owner.userId(),
				companyId);
		Member agent = member(companyId, "AGENT", "ACTIVE");

		assertThat(call(owner, companyId, "CHANGE_ROLE", agent.membershipId(), "ADMIN").getResponse().getStatus())
			.as("just logged in")
			.isEqualTo(200);

		clock.advance(Duration.ofMinutes(11));
		MvcResult stale = call(owner, companyId, "CHANGE_ROLE", agent.membershipId(), "ADMIN");
		assertThat(stale.getResponse().getStatus()).as("10m01s later").isEqualTo(403);
		assertThat(parse(stale).get("code").asString()).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(call(owner, companyId, "CHANGE_ROLE", agent.membershipId(), "VIEWER").getResponse().getStatus())
			.as("a change that does not involve Admin")
			.isEqualTo(200);
		assertThat(call(owner, companyId, "REMOVE", agent.membershipId(), null).getResponse().getStatus())
			.as("removing is not on the recent-authentication list")
			.isEqualTo(200);
	}

}
