package com.example.saas.api.invitation;

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

/** The invitation management endpoints over HTTP, against PostgreSQL (SPEC-INV, Phase 3). */
@Import(MutableClockConfiguration.class)
class InvitationApiTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private MutableClock clock;

	private record Person(UUID userId, String email, Cookie cookie) {
	}

	private record Team(UUID companyId, Map<Role, Person> people) {

		Person person(Role role) {
			return people.get(role);
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

	private void seat(UUID companyId, UUID userId, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, userId, role, status);
	}

	/** One company with a signed-in member in every role. */
	private Team team() throws Exception {
		Map<Role, Person> people = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			people.put(role, person());
		}
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Invites', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			for (Role role : Role.values()) {
				seat(id, people.get(role).userId(), role.name(), "ACTIVE");
			}
			return id;
		});
		return new Team(companyId, people);
	}

	private MvcResult send(Person who, HttpMethod method, String path, String body) throws Exception {
		String ip = freshIp();
		return api.send(method, path, body, who.cookie(), api.sessionCsrf(who.cookie(), ip), ip);
	}

	private static String invitations(UUID companyId) {
		return "/api/v1/companies/" + companyId + "/invitations";
	}

	private static String guest() {
		return "guest." + UUID.randomUUID() + "@example.com";
	}

	private MvcResult invite(Person who, UUID companyId, String email, String role) throws Exception {
		return send(who, HttpMethod.POST, invitations(companyId), json("email", email, "role", role));
	}

	private UUID created(Person who, UUID companyId, String role) throws Exception {
		MvcResult result = invite(who, companyId, guest(), role);
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(parse(result).get("id").asString());
	}

	private String status(UUID invitationId) {
		return jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitationId);
	}

	// ---- create

	@Test
	void anOwnerInvitesSomeoneAndTheResponseCarriesNoToken() throws Exception {
		Team team = team();
		String email = guest();

		MvcResult result = invite(team.person(Role.OWNER), team.companyId(), email, "AGENT");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = parse(result);
		assertThat(body.get("email").asString()).isEqualTo(email);
		assertThat(body.get("role").asString()).isEqualTo("AGENT");
		assertThat(body.get("status").asString()).isEqualTo("PENDING");
		assertThat(body.get("emailSent").asBoolean()).isFalse();
		assertThat(body.has("token") || body.has("tokenHash")).isFalse();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("token");
		assertThat(Duration.between(Instant.now(), Instant.parse(body.get("expiresAt").asString())))
			.isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1));
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ?",
				team.companyId(), email)).isEqualTo(1);
	}

	@Test
	void anAdminInvitesAgentsAndViewersButNotAdminsAndNobodyInvitesAnOwner() throws Exception {
		Team team = team();
		Person admin = team.person(Role.ADMIN);

		assertThat(invite(admin, team.companyId(), guest(), "AGENT").getResponse().getStatus()).isEqualTo(201);
		assertThat(invite(admin, team.companyId(), guest(), "VIEWER").getResponse().getStatus()).isEqualTo(201);
		MvcResult adminRole = invite(admin, team.companyId(), guest(), "ADMIN");
		MvcResult ownerRole = invite(team.person(Role.OWNER), team.companyId(), guest(), "OWNER");
		MvcResult adminOwnerRole = invite(admin, team.companyId(), guest(), "OWNER");

		assertThat(adminRole.getResponse().getStatus()).as("an Admin cannot create Admins").isEqualTo(403);
		assertThat(ownerRole.getResponse().getStatus()).isEqualTo(400);
		assertThat(parse(ownerRole).get("errors").get(0).get("field").asString()).isEqualTo("role");
		assertThat(adminOwnerRole.getResponse().getStatus()).as("an Admin asking for Owner is a bad request")
			.isEqualTo(400);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isEqualTo(2);
	}

	@Test
	void malformedInputIsABadRequestWithAFieldError() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		String path = invitations(team.companyId());

		MvcResult badEmail = send(owner, HttpMethod.POST, path, json("email", "not an address", "role", "AGENT"));
		MvcResult noRole = send(owner, HttpMethod.POST, path, json("email", guest()));
		MvcResult unknownRole = send(owner, HttpMethod.POST, path, json("email", guest(), "role", "SUPERUSER"));
		MvcResult extra = send(owner, HttpMethod.POST, path,
				"{\"email\":\"" + guest() + "\",\"role\":\"AGENT\",\"status\":\"ACCEPTED\"}");
		MvcResult huge = send(owner, HttpMethod.POST, path, json("email", "x".repeat(5_000) + "@example.com", "role", "AGENT"));

		assertThat(badEmail.getResponse().getStatus()).isEqualTo(400);
		assertThat(parse(badEmail).get("errors").get(0).get("code").asString()).isEqualTo("INVALID_EMAIL");
		assertThat(noRole.getResponse().getStatus()).isEqualTo(400);
		assertThat(parse(noRole).get("errors").get(0).get("code").asString()).isEqualTo("REQUIRED");
		assertThat(unknownRole.getResponse().getStatus()).isEqualTo(400);
		assertThat(extra.getResponse().getStatus()).as("unknown fields are rejected").isEqualTo(400);
		assertThat(huge.getResponse().getStatus()).isEqualTo(400);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isZero();
	}

	@Test
	void invitingAnAdminNeedsARecentLoginAndInvitingOthersDoesNot() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		clock.advance(Duration.ofMinutes(11));
		MvcResult stale = invite(owner, team.companyId(), guest(), "ADMIN");
		MvcResult plain = invite(owner, team.companyId(), guest(), "AGENT");

		assertThat(stale.getResponse().getStatus()).isEqualTo(403);
		assertThat(parse(stale).get("code").asString()).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(plain.getResponse().getStatus()).isEqualTo(201);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isEqualTo(1);
	}

	@Test
	void anAddressThatIsAlreadyAMemberOrAlreadyInvitedIsAConflict() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		Person suspended = person();
		tx.executeWithoutResult(s -> seat(team.companyId(), suspended.userId(), "AGENT", "SUSPENDED"));
		String email = guest();
		assertThat(invite(owner, team.companyId(), email, "AGENT").getResponse().getStatus()).isEqualTo(201);

		MvcResult member = invite(owner, team.companyId(), team.person(Role.AGENT).email(), "VIEWER");
		MvcResult suspendedMember = invite(owner, team.companyId(), suspended.email(), "VIEWER");
		MvcResult duplicate = invite(team.person(Role.ADMIN), team.companyId(), email, "VIEWER");

		for (MvcResult conflict : List.of(member, suspendedMember, duplicate)) {
			assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
			assertThat(parse(conflict).get("code").asString()).isEqualTo("CONFLICT");
		}
	}

	@Test
	void noInvitationOperationIsAllowedWhileTheCompanyIsPendingDeletion() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		UUID existing = created(owner, team.companyId(), "AGENT");
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", team.companyId());

		assertThat(invite(owner, team.companyId(), guest(), "AGENT").getResponse().getStatus()).isEqualTo(403);
		assertThat(send(owner, HttpMethod.POST, invitations(team.companyId()) + "/" + existing + "/resend", null)
			.getResponse().getStatus()).isEqualTo(403);
		assertThat(send(owner, HttpMethod.DELETE, invitations(team.companyId()) + "/" + existing, null).getResponse()
			.getStatus()).isEqualTo(403);
		assertThat(status(existing)).isEqualTo("PENDING");
	}

	// ---- list

	@Test
	void anOwnerAndAnAdminListThePendingInvitationsOfTheirCompanyOnly() throws Exception {
		Team team = team();
		Team other = team();
		UUID first = created(team.person(Role.OWNER), team.companyId(), "AGENT");
		clock.advance(Duration.ofSeconds(1));
		UUID revoked = created(team.person(Role.OWNER), team.companyId(), "VIEWER");
		send(team.person(Role.OWNER), HttpMethod.DELETE, invitations(team.companyId()) + "/" + revoked, null);
		clock.advance(Duration.ofSeconds(1));
		UUID last = created(team.person(Role.ADMIN), team.companyId(), "VIEWER");
		created(other.person(Role.OWNER), other.companyId(), "AGENT");

		for (Role role : new Role[] { Role.OWNER, Role.ADMIN }) {
			MvcResult result = api.get(invitations(team.companyId()), team.person(role).cookie(), freshIp());
			assertThat(result.getResponse().getStatus()).as(role.name()).isEqualTo(200);
			assertThat(parse(result).findValuesAsString("id")).containsExactly(last.toString(), first.toString());
			assertThat(result.getResponse().getContentAsString()).doesNotContain("token");
		}
	}

	// ---- resend and revoke

	@Test
	void resendingGivesANewExpiryAndRevokingMakesItFinal() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		UUID id = created(owner, team.companyId(), "AGENT");
		String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
		jdbc.update("UPDATE invitation SET token_hash = ?, expires_at = created_at + interval '1 hour' WHERE id = ?", hash,
				id);

		MvcResult resent = send(team.person(Role.ADMIN), HttpMethod.POST,
				invitations(team.companyId()) + "/" + id + "/resend", null);

		assertThat(resent.getResponse().getStatus()).isEqualTo(200);
		assertThat(parse(resent).get("emailSent").asBoolean()).as("the new token comes with the new email").isFalse();
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", hash)).as("the old token is dead").isZero();
		assertThat(Duration.between(Instant.now(), Instant.parse(parse(resent).get("expiresAt").asString())))
			.isGreaterThan(Duration.ofDays(7).minusMinutes(1));

		MvcResult revoked = send(owner, HttpMethod.DELETE, invitations(team.companyId()) + "/" + id, null);
		assertThat(revoked.getResponse().getStatus()).isEqualTo(204);
		assertThat(status(id)).isEqualTo("REVOKED");
		assertThat(send(owner, HttpMethod.DELETE, invitations(team.companyId()) + "/" + id, null).getResponse()
			.getStatus()).as("revoking twice").isEqualTo(409);
		assertThat(send(owner, HttpMethod.POST, invitations(team.companyId()) + "/" + id + "/resend", null).getResponse()
			.getStatus()).as("resending a revoked invitation").isEqualTo(409);
	}

	@Test
	void anAdminCannotResendOrRevokeAnAdminInvitationAndResendingOneNeedsARecentLogin() throws Exception {
		Team team = team();
		UUID adminInvitation = created(team.person(Role.OWNER), team.companyId(), "ADMIN");
		String resend = invitations(team.companyId()) + "/" + adminInvitation + "/resend";
		String revoke = invitations(team.companyId()) + "/" + adminInvitation;

		assertThat(send(team.person(Role.ADMIN), HttpMethod.POST, resend, null).getResponse().getStatus()).isEqualTo(403);
		assertThat(send(team.person(Role.ADMIN), HttpMethod.DELETE, revoke, null).getResponse().getStatus())
			.isEqualTo(403);
		clock.advance(Duration.ofMinutes(11));
		MvcResult stale = send(team.person(Role.OWNER), HttpMethod.POST, resend, null);
		assertThat(stale.getResponse().getStatus()).isEqualTo(403);
		assertThat(parse(stale).get("code").asString()).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(send(team.person(Role.OWNER), HttpMethod.DELETE, revoke, null).getResponse().getStatus())
			.as("revoking needs no recent login").isEqualTo(204);
	}

	// ---- authorization, isolation, CSRF

	@Test
	void everyOperationForEveryRoleMatchesTheMatrixOverHttp() throws Exception {
		Team team = team();
		Person outsider = person();
		String base = invitations(team.companyId());
		UUID seeded = created(team.person(Role.OWNER), team.companyId(), "AGENT");

		for (Role role : Role.values()) {
			boolean mayManage = role == Role.OWNER || role == Role.ADMIN;
			Person who = team.person(role);
			assertThat(invite(who, team.companyId(), guest(), "VIEWER").getResponse().getStatus()).as("%s creates", role)
				.isEqualTo(mayManage ? 201 : 403);
			assertThat(api.get(base, who.cookie(), freshIp()).getResponse().getStatus()).as("%s lists", role)
				.isEqualTo(mayManage ? 200 : 403);
			assertThat(send(who, HttpMethod.POST, base + "/" + seeded + "/resend", null).getResponse().getStatus())
				.as("%s resends", role).isEqualTo(mayManage ? 200 : 403);
		}
		for (Role role : Role.values()) {
			// Each role revokes an invitation of its own, so one revoke does not hide the next.
			UUID target = created(team.person(Role.OWNER), team.companyId(), "AGENT");
			assertThat(send(team.person(role), HttpMethod.DELETE, base + "/" + target, null).getResponse().getStatus())
				.as("%s revokes", role).isEqualTo(role == Role.OWNER || role == Role.ADMIN ? 204 : 403);
		}
		assertThat(invite(outsider, team.companyId(), guest(), "VIEWER").getResponse().getStatus()).as("outsider creates")
			.isEqualTo(404);
		assertThat(api.get(base, outsider.cookie(), freshIp()).getResponse().getStatus()).as("outsider lists").isEqualTo(404);
		assertThat(send(outsider, HttpMethod.POST, base + "/" + seeded + "/resend", null).getResponse().getStatus())
			.as("outsider resends").isEqualTo(404);
		assertThat(send(outsider, HttpMethod.DELETE, base + "/" + seeded, null).getResponse().getStatus())
			.as("outsider revokes").isEqualTo(404);
	}

	@Test
	void anInvitationIdFromAnotherCompanyIsNotFoundAndNothingChanges() throws Exception {
		Team mine = team();
		Team theirs = team();
		UUID foreign = created(theirs.person(Role.OWNER), theirs.companyId(), "AGENT");
		Person owner = mine.person(Role.OWNER);

		assertThat(send(owner, HttpMethod.POST, invitations(mine.companyId()) + "/" + foreign + "/resend", null)
			.getResponse().getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.DELETE, invitations(mine.companyId()) + "/" + foreign, null).getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.DELETE, invitations(theirs.companyId()) + "/" + foreign, null).getResponse()
			.getStatus()).as("an Owner of another company").isEqualTo(404);
		assertThat(status(foreign)).isEqualTo("PENDING");
	}

	@Test
	void malformedOrShortenedIdsAreNotFound() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		assertThat(send(owner, HttpMethod.DELETE, invitations(team.companyId()) + "/not-a-uuid", null).getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.POST, invitations(team.companyId()) + "/1-1-1-1-1/resend", null).getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(send(owner, HttpMethod.POST, "/api/v1/companies/zzz/invitations", json("email", guest(), "role", "AGENT"))
			.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void everyChangingRouteNeedsTheSessionBoundCsrfToken() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		UUID id = created(owner, team.companyId(), "AGENT");
		String ip = freshIp();
		String base = invitations(team.companyId());

		for (MvcResult result : List.of(
				api.send(HttpMethod.POST, base, json("email", guest(), "role", "AGENT"), owner.cookie(), null, ip),
				api.send(HttpMethod.POST, base + "/" + id + "/resend", null, owner.cookie(), null, ip),
				api.send(HttpMethod.DELETE, base + "/" + id, null, owner.cookie(), null, ip))) {
			assertThat(result.getResponse().getStatus()).isEqualTo(403);
		}
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", team.companyId())).isEqualTo(1);
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(api.send(HttpMethod.POST, base, json("email", guest(), "role", "AGENT"), null, null, ip).getResponse()
			.getStatus()).as("no session").isIn(401, 403);
	}

	// ---- limits and audit

	@Test
	void the21stInvitationByOneInviterIsThrottledWithRetryAfter() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		for (int i = 0; i < 20; i++) {
			assertThat(invite(owner, team.companyId(), guest(), "VIEWER").getResponse().getStatus()).as("invitation %d", i + 1)
				.isEqualTo(201);
		}
		MvcResult twentyFirst = invite(owner, team.companyId(), guest(), "VIEWER");

		assertThat(twentyFirst.getResponse().getStatus()).isEqualTo(429);
		assertThat(twentyFirst.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(parse(twentyFirst).get("code").asString()).isEqualTo("RATE_LIMITED");
		assertThat(invite(team.person(Role.ADMIN), team.companyId(), guest(), "VIEWER").getResponse().getStatus())
			.as("another inviter is unaffected").isEqualTo(201);
	}

	@Test
	void the4thSendToOneAddressWithin24HoursIsThrottled() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		String email = guest();
		UUID id = UUID.fromString(parse(invite(owner, team.companyId(), email, "AGENT")).get("id").asString());
		String resend = invitations(team.companyId()) + "/" + id + "/resend";

		assertThat(send(owner, HttpMethod.POST, resend, null).getResponse().getStatus()).isEqualTo(200);
		assertThat(send(owner, HttpMethod.POST, resend, null).getResponse().getStatus()).isEqualTo(200);
		MvcResult fourth = send(owner, HttpMethod.POST, resend, null);

		assertThat(fourth.getResponse().getStatus()).isEqualTo(429);
		assertThat(fourth.getResponse().getHeader("Retry-After")).isNotNull();
	}

	@Test
	void everyChangeIsAuditedWithoutTheAddressAndADeniedCallerLeavesADenialRecord() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		String email = guest();
		UUID id = UUID.fromString(parse(invite(owner, team.companyId(), email, "AGENT")).get("id").asString());
		send(owner, HttpMethod.POST, invitations(team.companyId()) + "/" + id + "/resend", null);
		send(owner, HttpMethod.DELETE, invitations(team.companyId()) + "/" + id, null);
		invite(team.person(Role.VIEWER), team.companyId(), guest(), "AGENT");

		List<String> rows = jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_event a WHERE company_id = ? "
				+ "AND event_type LIKE 'invitation.%'", String.class, team.companyId());

		assertThat(rows).hasSize(3);
		for (String row : rows) {
			assertThat(row).doesNotContain("@example.com", "token");
		}
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'invitation.revoked' AND target_id = ? "
				+ "AND before_value = 'PENDING' AND after_value = 'REVOKED'", id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' "
				+ "AND company_id = ? AND actor_id = ? AND reason_code = 'invite_members'", team.companyId(),
				team.person(Role.VIEWER).userId())).as("written by the handler after the request").isEqualTo(1);
	}

}
