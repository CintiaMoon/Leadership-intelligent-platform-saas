package com.example.saas.api.tenant;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.MutableClock;
import com.example.saas.api.MutableClockConfiguration;
import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.parse;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** The member endpoints over HTTP, against PostgreSQL (SPEC-MEM, Phase 3). */
@Import(MutableClockConfiguration.class)
class MemberApiTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private MutableClock clock;

	private record Person(UUID userId, String email, Cookie cookie) {
	}

	private record Seat(Person person, UUID membershipId) {
	}

	private record Team(UUID companyId, Map<Role, Seat> seats) {

		Person person(Role role) {
			return seats.get(role).person();
		}

		UUID membership(Role role) {
			return seats.get(role).membershipId();
		}

	}

	@BeforeEach
	void resetClock() {
		clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	private Person person() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new Person(userId, email, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	private UUID join(UUID companyId, UUID userId, String role, String status) {
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, userId, role, status);
	}

	/** One company with an ACTIVE member in every role, all signed in. */
	private Team team() throws Exception {
		Map<Role, Person> people = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			people.put(role, person());
		}
		Map<Role, Seat> seats = new EnumMap<>(Role.class);
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Members', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			for (Role role : Role.values()) {
				Person p = people.get(role);
				seats.put(role, new Seat(p, join(id, p.userId(), role.name(), "ACTIVE")));
			}
			return id;
		});
		return new Team(companyId, seats);
	}

	private MvcResult send(Person who, HttpMethod method, String path, String body) throws Exception {
		String ip = freshIp();
		return api.send(method, path, body, who.cookie(), api.sessionCsrf(who.cookie(), ip), ip);
	}

	private static String members(UUID companyId) {
		return "/api/v1/companies/" + companyId + "/members";
	}

	private static String member(UUID companyId, UUID membershipId) {
		return members(companyId) + "/" + membershipId;
	}

	private MvcResult get(Person who, String path) throws Exception {
		return api.get(path, who.cookie(), freshIp());
	}

	private String status(UUID membershipId) {
		return jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, membershipId);
	}

	private String role(UUID membershipId) {
		return jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, membershipId);
	}

	// ---- member list

	@Test
	void anOwnerAndAnAdminSeeTheMembersAndAnAgentAndAViewerDoNot() throws Exception {
		Team team = team();
		UUID suspended = tx.execute(s -> join(team.companyId(), newUserId(), "AGENT", "SUSPENDED"));
		UUID removed = tx.execute(s -> join(team.companyId(), newUserId(), "AGENT", "REMOVED"));

		MvcResult asOwner = get(team.person(Role.OWNER), members(team.companyId()));
		JsonNode list = parse(asOwner);

		assertThat(asOwner.getResponse().getStatus()).isEqualTo(200);
		assertThat(list.findValuesAsString("id")).contains(team.membership(Role.OWNER).toString(),
				suspended.toString()).doesNotContain(removed.toString());
		assertThat(list.size()).as("four members and one suspended; the removed one is not listed").isEqualTo(5);
		for (JsonNode item : list) {
			assertThat(item.has("userId")).as("no user ID is exposed").isFalse();
			assertThat(item.get("email").asString()).contains("@");
			assertThat(item.get("self").asBoolean()).isEqualTo(item.get("id").asString()
				.equals(team.membership(Role.OWNER).toString()));
		}
		assertThat(get(team.person(Role.ADMIN), members(team.companyId())).getResponse().getStatus()).isEqualTo(200);
		assertThat(get(team.person(Role.AGENT), members(team.companyId())).getResponse().getStatus()).isEqualTo(403);
		assertThat(get(team.person(Role.VIEWER), members(team.companyId())).getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void aStrangerGetsNotFoundForTheMemberList() throws Exception {
		Team team = team();

		assertThat(get(person(), members(team.companyId())).getResponse().getStatus()).isEqualTo(404);
	}

	// ---- role change

	@Test
	void anOwnerChangesARoleAndTheChangeIsAuditedWithBeforeAndAfter() throws Exception {
		Team team = team();

		MvcResult result = send(team.person(Role.OWNER), HttpMethod.PATCH,
				member(team.companyId(), team.membership(Role.AGENT)), json("role", "VIEWER"));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(parse(result).get("role").asString()).isEqualTo("VIEWER");
		assertThat(role(team.membership(Role.AGENT))).isEqualTo("VIEWER");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.role.changed' "
				+ "AND company_id = ? AND actor_id = ? AND target_id = ? AND before_value = 'AGENT' "
				+ "AND after_value = 'VIEWER'", team.companyId(), team.person(Role.OWNER).userId(),
				team.membership(Role.AGENT))).isEqualTo(1);
	}

	@Test
	void anAdminChangesAnAgentToAViewerButCannotMakeAnAdminOrTouchAnAdminOrTheOwner() throws Exception {
		Team team = team();
		Person admin = team.person(Role.ADMIN);

		assertThat(send(admin, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "VIEWER")).getResponse().getStatus()).isEqualTo(200);
		MvcResult promote = send(admin, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.VIEWER)),
				json("role", "ADMIN"));
		MvcResult onOwner = send(admin, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.OWNER)),
				json("role", "AGENT"));
		MvcResult onSelf = send(admin, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.ADMIN)),
				json("role", "AGENT"));

		assertThat(promote.getResponse().getStatus()).as("an Admin cannot create Admins").isEqualTo(403);
		assertThat(onOwner.getResponse().getStatus()).isEqualTo(403);
		assertThat(onSelf.getResponse().getStatus()).as("no self change (AC-MEM-12)").isEqualTo(403);
		assertThat(role(team.membership(Role.VIEWER))).isEqualTo("VIEWER");
		assertThat(role(team.membership(Role.OWNER))).isEqualTo("OWNER");
		assertThat(role(team.membership(Role.ADMIN))).isEqualTo("ADMIN");
	}

	@Test
	void anAgentAndAViewerCannotChangeRoles() throws Exception {
		Team team = team();

		for (Role actor : new Role[] { Role.AGENT, Role.VIEWER }) {
			MvcResult result = send(team.person(actor), HttpMethod.PATCH,
					member(team.companyId(), team.membership(Role.VIEWER)), json("role", "AGENT"));
			assertThat(result.getResponse().getStatus()).as(actor.name()).isEqualTo(403);
		}
		assertThat(role(team.membership(Role.VIEWER))).isEqualTo("VIEWER");
	}

	@Test
	void theOwnerMembershipCannotBeChangedAndOwnerCannotBeAssigned() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		MvcResult demoteSelf = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.OWNER)),
				json("role", "ADMIN"));
		MvcResult assignOwner = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "OWNER"));
		MvcResult unknownRole = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "SUPERUSER"));
		MvcResult missing = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)), "{}");
		MvcResult extra = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				"{\"role\":\"VIEWER\",\"status\":\"ACTIVE\"}");

		assertThat(demoteSelf.getResponse().getStatus()).isEqualTo(409);
		assertThat(parse(demoteSelf).get("code").asString()).isEqualTo("CONFLICT");
		assertThat(assignOwner.getResponse().getStatus()).isEqualTo(400);
		assertThat(unknownRole.getResponse().getStatus()).isEqualTo(400);
		assertThat(missing.getResponse().getStatus()).isEqualTo(400);
		assertThat(parse(missing).get("errors").get(0).get("code").asString()).isEqualTo("REQUIRED");
		assertThat(extra.getResponse().getStatus()).as("unknown fields are rejected").isEqualTo(400);
		assertThat(role(team.membership(Role.OWNER))).isEqualTo("OWNER");
		assertThat(role(team.membership(Role.AGENT))).isEqualTo("AGENT");
	}

	@Test
	void aNoOpRoleChangeAndAChangeOfASuspendedMemberAreConflicts() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		UUID suspended = tx.execute(s -> join(team.companyId(), newUserId(), "AGENT", "SUSPENDED"));

		assertThat(send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "AGENT")).getResponse().getStatus()).isEqualTo(409);
		assertThat(send(owner, HttpMethod.PATCH, member(team.companyId(), suspended), json("role", "VIEWER"))
			.getResponse().getStatus()).isEqualTo(409);
	}

	@Test
	void promotingToAdminNeedsARecentLoginAndOtherChangesDoNot() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		clock.advance(Duration.ofMinutes(11));
		MvcResult stale = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "ADMIN"));
		MvcResult plain = send(owner, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "VIEWER"));

		assertThat(stale.getResponse().getStatus()).isEqualTo(403);
		assertThat(parse(stale).get("code").asString()).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(role(team.membership(Role.AGENT))).as("the refused promotion changed nothing")
			.isEqualTo("VIEWER");
		assertThat(plain.getResponse().getStatus()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.role.changed' "
				+ "AND target_id = ?", team.membership(Role.AGENT))).as("only the successful change is audited")
			.isEqualTo(1);
	}

	// ---- suspend and reactivate

	@Test
	void suspendingDeniesTheMemberOnTheNextRequestKeepsTheRoleAndRecordsWhoAndWhen() throws Exception {
		Team team = team();
		Person agent = team.person(Role.AGENT);
		String companyPath = "/api/v1/companies/" + team.companyId();
		assertThat(get(agent, companyPath).getResponse().getStatus()).isEqualTo(200);

		MvcResult result = send(team.person(Role.ADMIN), HttpMethod.POST,
				member(team.companyId(), team.membership(Role.AGENT)) + "/suspend", null);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(parse(result).get("status").asString()).isEqualTo("SUSPENDED");
		assertThat(parse(result).get("role").asString()).isEqualTo("AGENT");
		assertThat(get(agent, companyPath).getResponse().getStatus()).as("same session, next request").isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT status_changed_by = ? AND status_changed_at IS NOT NULL "
				+ "FROM membership WHERE id = ?", Boolean.class, team.person(Role.ADMIN).userId(),
				team.membership(Role.AGENT))).isTrue();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.suspended' "
				+ "AND target_id = ? AND before_value = 'ACTIVE' AND after_value = 'SUSPENDED'",
				team.membership(Role.AGENT))).isEqualTo(1);
	}

	@Test
	void reactivatingRestoresAccessWithThePreservedRoleAndRecordsTheActor() throws Exception {
		Team team = team();
		Person agent = team.person(Role.AGENT);
		send(team.person(Role.OWNER), HttpMethod.POST, member(team.companyId(), team.membership(Role.AGENT)) + "/suspend",
				null);

		MvcResult result = send(team.person(Role.ADMIN), HttpMethod.POST,
				member(team.companyId(), team.membership(Role.AGENT)) + "/reactivate", null);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(parse(result).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(get(agent, "/api/v1/companies/" + team.companyId()).getResponse().getStatus()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT status_changed_by FROM membership WHERE id = ?", UUID.class,
				team.membership(Role.AGENT))).isEqualTo(team.person(Role.ADMIN).userId());
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.reactivated' "
				+ "AND target_id = ? AND before_value = 'SUSPENDED' AND after_value = 'ACTIVE'",
				team.membership(Role.AGENT))).isEqualTo(1);
	}

	@Test
	void suspendingAndReactivatingFollowTheAdminLimitsAndTheStateRules() throws Exception {
		Team team = team();
		Person admin = team.person(Role.ADMIN);
		String agentPath = member(team.companyId(), team.membership(Role.AGENT));

		assertThat(send(admin, HttpMethod.POST, member(team.companyId(), team.membership(Role.OWNER)) + "/suspend", null)
			.getResponse().getStatus()).as("Admin on Owner").isEqualTo(403);
		assertThat(send(team.person(Role.OWNER), HttpMethod.POST,
				member(team.companyId(), team.membership(Role.OWNER)) + "/suspend", null).getResponse().getStatus())
			.as("Owner on Owner").isEqualTo(409);
		assertThat(send(admin, HttpMethod.POST, agentPath + "/reactivate", null).getResponse().getStatus())
			.as("reactivating an active member").isEqualTo(409);
		assertThat(send(admin, HttpMethod.POST, agentPath + "/suspend", null).getResponse().getStatus()).isEqualTo(200);
		assertThat(send(admin, HttpMethod.POST, agentPath + "/suspend", null).getResponse().getStatus())
			.as("suspending twice").isEqualTo(409);
		assertThat(send(team.person(Role.VIEWER), HttpMethod.POST, agentPath + "/reactivate", null).getResponse()
			.getStatus()).isEqualTo(403);
	}

	// ---- remove

	@Test
	void removingEndsAccessOnTheNextRequestAndOnlyAnOwnerRemovesAnAdmin() throws Exception {
		Team team = team();
		Person agent = team.person(Role.AGENT);
		String companyPath = "/api/v1/companies/" + team.companyId();

		assertThat(send(team.person(Role.ADMIN), HttpMethod.DELETE, member(team.companyId(), team.membership(Role.ADMIN)),
				null).getResponse().getStatus()).as("an Admin removing an Admin (self)").isEqualTo(403);
		MvcResult removeAgent = send(team.person(Role.ADMIN), HttpMethod.DELETE,
				member(team.companyId(), team.membership(Role.AGENT)), null);
		MvcResult removeAdmin = send(team.person(Role.OWNER), HttpMethod.DELETE,
				member(team.companyId(), team.membership(Role.ADMIN)), null);

		assertThat(removeAgent.getResponse().getStatus()).isEqualTo(204);
		assertThat(removeAdmin.getResponse().getStatus()).isEqualTo(204);
		assertThat(get(agent, companyPath).getResponse().getStatus()).as("same session, next request").isEqualTo(404);
		assertThat(status(team.membership(Role.AGENT))).isEqualTo("REMOVED");
		assertThat(jdbc.queryForObject("SELECT status_changed_by FROM membership WHERE id = ?", UUID.class,
				team.membership(Role.ADMIN))).isEqualTo(team.person(Role.OWNER).userId());
		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", agent.userId()))
			.as("the user account is not deleted")
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.removed' AND company_id = ?",
				team.companyId())).isEqualTo(2);
		List<String> ids = parse(get(team.person(Role.OWNER), members(team.companyId()))).findValuesAsString("id");
		assertThat(ids).doesNotContain(team.membership(Role.AGENT).toString(), team.membership(Role.ADMIN).toString());
	}

	@Test
	void aSuspendedMemberCanBeRemovedAndARemovedOneCannotBeRemovedAgainOrReactivated() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		String agentPath = member(team.companyId(), team.membership(Role.AGENT));
		send(owner, HttpMethod.POST, agentPath + "/suspend", null);

		assertThat(send(owner, HttpMethod.DELETE, agentPath, null).getResponse().getStatus()).isEqualTo(204);
		assertThat(send(owner, HttpMethod.DELETE, agentPath, null).getResponse().getStatus()).isEqualTo(409);
		assertThat(send(owner, HttpMethod.POST, agentPath + "/reactivate", null).getResponse().getStatus())
			.isEqualTo(409);
		assertThat(send(owner, HttpMethod.DELETE, member(team.companyId(), team.membership(Role.OWNER)), null)
			.getResponse().getStatus()).as("the Owner cannot be removed").isEqualTo(409);
		assertThat(status(team.membership(Role.OWNER))).isEqualTo("ACTIVE");
	}

	// ---- leave

	@Test
	void aMemberLeavesLosesAccessKeepsTheAccountAndGetsANewSessionIdentifier() throws Exception {
		Team team = team();
		Person agent = team.person(Role.AGENT);
		UUID otherCompany = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Other', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			join(id, newUserId(), "OWNER", "ACTIVE");
			join(id, agent.userId(), "VIEWER", "ACTIVE");
			return id;
		});
		String ip = freshIp();
		String csrf = api.sessionCsrf(agent.cookie(), ip);

		MvcResult result = api.send(HttpMethod.POST, "/api/v1/companies/" + team.companyId() + "/leave", null,
				agent.cookie(), csrf, ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		Cookie rotated = sessionCookie(result);
		assertThat(rotated).as("a new session cookie").isNotNull();
		assertThat(rotated.getValue()).isNotEqualTo(agent.cookie().getValue());
		assertThat(api.get("/api/v1/auth/session", agent.cookie(), freshIp()).getResponse().getStatus())
			.as("the old identifier")
			.isEqualTo(401);
		assertThat(api.get("/api/v1/auth/session", rotated, freshIp()).getResponse().getStatus()).isEqualTo(200);
		assertThat(api.get("/api/v1/companies/" + team.companyId(), rotated, freshIp()).getResponse().getStatus())
			.as("access to the company ended")
			.isEqualTo(404);
		assertThat(api.get("/api/v1/companies/" + otherCompany, rotated, freshIp()).getResponse().getStatus())
			.as("other companies are unaffected")
			.isEqualTo(200);
		assertThat(status(team.membership(Role.AGENT))).isEqualTo("REMOVED");
		assertThat(jdbc.queryForObject("SELECT status_changed_by FROM membership WHERE id = ?", UUID.class,
				team.membership(Role.AGENT))).as("recorded as done by the member").isEqualTo(agent.userId());
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.left' AND actor_id = ? "
				+ "AND before_value = 'ACTIVE' AND after_value = 'REMOVED'", agent.userId())).isEqualTo(1);
	}

	@Test
	void anAdminAnAgentAndAViewerCanLeaveButTheOwnerCannot() throws Exception {
		Team team = team();

		MvcResult owner = send(team.person(Role.OWNER), HttpMethod.POST, "/api/v1/companies/" + team.companyId() + "/leave",
				null);

		assertThat(owner.getResponse().getStatus()).isEqualTo(403);
		assertThat(status(team.membership(Role.OWNER))).isEqualTo("ACTIVE");
		for (Role role : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
			assertThat(send(team.person(role), HttpMethod.POST, "/api/v1/companies/" + team.companyId() + "/leave", null)
				.getResponse().getStatus()).as(role.name()).isEqualTo(204);
		}
	}

	@Test
	void aStrangerAndASuspendedMemberCannotLeaveACompanyTheyAreNotActiveIn() throws Exception {
		Team team = team();
		Person stranger = person();
		String leave = "/api/v1/companies/" + team.companyId() + "/leave";

		assertThat(send(stranger, HttpMethod.POST, leave, null).getResponse().getStatus()).isEqualTo(404);
		send(team.person(Role.OWNER), HttpMethod.POST, member(team.companyId(), team.membership(Role.AGENT)) + "/suspend", null);
		assertThat(send(team.person(Role.AGENT), HttpMethod.POST, leave, null).getResponse().getStatus())
			.as("a suspended member has no access to leave through").isEqualTo(404);
		assertThat(status(team.membership(Role.AGENT))).isEqualTo("SUSPENDED");
	}

	// ---- isolation, CSRF, audit hygiene

	@Test
	void aMembershipIdFromAnotherCompanyIsRejectedForEveryRouteAndChangesNothing() throws Exception {
		Team mine = team();
		Team theirs = team();
		Person owner = mine.person(Role.OWNER);
		String foreign = member(mine.companyId(), theirs.membership(Role.AGENT));

		assertThat(send(owner, HttpMethod.PATCH, foreign, json("role", "VIEWER")).getResponse().getStatus())
			.isEqualTo(404);
		assertThat(send(owner, HttpMethod.POST, foreign + "/suspend", null).getResponse().getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.POST, foreign + "/reactivate", null).getResponse().getStatus())
			.isEqualTo(404);
		assertThat(send(owner, HttpMethod.DELETE, foreign, null).getResponse().getStatus()).isEqualTo(404);
		assertThat(role(theirs.membership(Role.AGENT))).isEqualTo("AGENT");
		assertThat(status(theirs.membership(Role.AGENT))).isEqualTo("ACTIVE");
		// And the owner of one company cannot use their role in another.
		assertThat(send(owner, HttpMethod.POST, member(theirs.companyId(), theirs.membership(Role.AGENT)) + "/suspend",
				null).getResponse().getStatus()).isEqualTo(404);
		assertThat(status(theirs.membership(Role.AGENT))).isEqualTo("ACTIVE");
	}

	@Test
	void aMalformedOrShortenedIdIsNotFound() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		assertThat(send(owner, HttpMethod.POST, members(team.companyId()) + "/not-a-uuid/suspend", null).getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.POST, members(team.companyId()) + "/1-1-1-1-1/suspend", null).getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.DELETE, "/api/v1/companies/zzz/members/" + team.membership(Role.AGENT), null)
			.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void everyChangingRouteNeedsTheSessionBoundCsrfToken() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		String ip = freshIp();
		String agentPath = member(team.companyId(), team.membership(Role.AGENT));

		for (MvcResult result : List.of(
				api.send(HttpMethod.PATCH, agentPath, json("role", "VIEWER"), owner.cookie(), null, ip),
				api.send(HttpMethod.POST, agentPath + "/suspend", null, owner.cookie(), null, ip),
				api.send(HttpMethod.POST, agentPath + "/reactivate", null, owner.cookie(), null, ip),
				api.send(HttpMethod.DELETE, agentPath, null, owner.cookie(), null, ip),
				api.send(HttpMethod.POST, "/api/v1/companies/" + team.companyId() + "/leave", null, owner.cookie(), null,
						ip))) {
			assertThat(result.getResponse().getStatus()).isEqualTo(403);
		}
		assertThat(status(team.membership(Role.AGENT))).isEqualTo("ACTIVE");
		assertThat(role(team.membership(Role.AGENT))).isEqualTo("AGENT");
		assertThat(api.send(HttpMethod.POST, agentPath + "/suspend", null, null, null, ip).getResponse().getStatus())
			.as("no session")
			.isIn(401, 403);
	}

	@Test
	void auditRowsHoldIdentifiersAndEnumValuesOnly() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		String agentPath = member(team.companyId(), team.membership(Role.AGENT));
		send(owner, HttpMethod.PATCH, agentPath, json("role", "VIEWER"));
		send(owner, HttpMethod.POST, agentPath + "/suspend", null);
		send(owner, HttpMethod.POST, agentPath + "/reactivate", null);
		send(owner, HttpMethod.DELETE, agentPath, null);
		send(team.person(Role.VIEWER), HttpMethod.POST, "/api/v1/companies/" + team.companyId() + "/leave", null);

		List<String> rows = jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_event a WHERE company_id = ? "
				+ "AND event_type LIKE 'membership.%'", String.class, team.companyId());

		assertThat(rows).hasSize(5);
		for (String row : rows) {
			assertThat(row).doesNotContain("@example.com", "Verified Person", "password", "token");
		}
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type LIKE 'membership.%' "
				+ "AND (COALESCE(before_value, '') !~ '^[A-Z_]+$' OR COALESCE(after_value, '') !~ '^[A-Z_]+$')",
				team.companyId())).as("every membership audit row has both values, as enum names").isZero();
	}

	@Test
	void aDisabledAccountLosesItsSessionsEverywhere() throws Exception {
		Team team = team();
		Person admin = team.person(Role.ADMIN);
		assertThat(get(admin, members(team.companyId())).getResponse().getStatus()).isEqualTo(200);

		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", admin.userId());

		assertThat(get(admin, members(team.companyId())).getResponse().getStatus()).isEqualTo(401);
		assertThat(get(admin, "/api/v1/auth/session").getResponse().getStatus()).isEqualTo(401);
	}

	// ---- the real-endpoint matrix

	@Test
	void everyActorAndTargetRoleForSuspendAndRemoveMatchesTheMatrixOverHttp() throws Exception {
		for (Role actorRole : Role.values()) {
			Person actor = person();
			Map<Role, UUID> targets = new EnumMap<>(Role.class);
			UUID companyId = tx.execute(s -> {
				UUID id = jdbc.queryForObject(
						"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Matrix', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
						UUID.class);
				if (actorRole == Role.OWNER) {
					join(id, actor.userId(), "OWNER", "ACTIVE");
				}
				else {
					jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
							+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, newUserId());
					join(id, actor.userId(), actorRole.name(), "ACTIVE");
				}
				for (Role target : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
					targets.put(target, join(id, newUserId(), target.name(), "ACTIVE"));
				}
				return id;
			});
			for (Role target : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
				boolean allowed = actorRole == Role.OWNER || (actorRole == Role.ADMIN && target != Role.ADMIN);
				assertThat(send(actor, HttpMethod.POST, member(companyId, targets.get(target)) + "/suspend", null)
					.getResponse().getStatus()).as("%s suspends %s", actorRole, target).isEqualTo(allowed ? 200 : 403);
				assertThat(send(actor, HttpMethod.POST, member(companyId, targets.get(target)) + "/reactivate", null)
					.getResponse().getStatus()).as("%s reactivates %s", actorRole, target)
					.isEqualTo(allowed ? 200 : 403);
				assertThat(send(actor, HttpMethod.DELETE, member(companyId, targets.get(target)), null).getResponse()
					.getStatus()).as("%s removes %s", actorRole, target).isEqualTo(allowed ? 204 : 403);
				assertThat(status(targets.get(target))).isEqualTo(allowed ? "REMOVED" : "ACTIVE");
			}
		}
	}

	private UUID newUserId() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	@Test
	void anAttemptToAssignOwnerByAMemberWithNoRightsIsRefusedAndRecordedAsADenial() throws Exception {
		Team team = team();
		Person viewer = team.person(Role.VIEWER);

		MvcResult attempt = send(viewer, HttpMethod.PATCH, member(team.companyId(), team.membership(Role.AGENT)),
				json("role", "OWNER"));

		assertThat(attempt.getResponse().getStatus()).as("403, not the 400 an Owner would get").isEqualTo(403);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' "
				+ "AND company_id = ? AND actor_id = ? AND reason_code = 'change_member_role'", team.companyId(),
				viewer.userId())).as("the attempted escalation is on record").isEqualTo(1);
		assertThat(role(team.membership(Role.AGENT))).isEqualTo("AGENT");
	}

	@Test
	void whileACompanyIsPendingDeletionNoMemberChangeIsAllowedToAnyone() throws Exception {
		Team team = team();
		TestLifecycle.markPendingDeletion(jdbc, team.companyId());
		Person owner = team.person(Role.OWNER);
		Person admin = team.person(Role.ADMIN);
		String agentPath = member(team.companyId(), team.membership(Role.AGENT));

		for (Person actor : List.of(owner, admin)) {
			assertThat(send(actor, HttpMethod.PATCH, agentPath, json("role", "VIEWER")).getResponse().getStatus())
				.isEqualTo(403);
			assertThat(send(actor, HttpMethod.POST, agentPath + "/suspend", null).getResponse().getStatus())
				.isEqualTo(403);
			assertThat(send(actor, HttpMethod.DELETE, agentPath, null).getResponse().getStatus()).isEqualTo(403);
		}
		assertThat(send(team.person(Role.AGENT), HttpMethod.POST, "/api/v1/companies/" + team.companyId() + "/leave",
				null).getResponse().getStatus()).as("not even leaving (AC-DEL-12)").isEqualTo(403);
		assertThat(get(owner, members(team.companyId())).getResponse().getStatus()).as("the Owner may still read")
			.isEqualTo(200);
		assertThat(get(admin, members(team.companyId())).getResponse().getStatus()).as("other roles may not read")
			.isEqualTo(403);
		assertThat(status(team.membership(Role.AGENT))).isEqualTo("ACTIVE");
		assertThat(role(team.membership(Role.AGENT))).isEqualTo("AGENT");
	}

}
