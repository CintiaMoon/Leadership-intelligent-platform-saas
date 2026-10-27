package com.example.saas.api.plan;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

/**
 * Every existing operation across subscription states and roles over HTTP, against PostgreSQL (plan Q-08, Q-10, Q-14;
 * AC-PLAN-03, 04, 07, 08, 13, 19, 20, 22): the stable code and state of a refusal, the denial audit, the order of the
 * checks, and the subscription endpoint. The clock moves past the trial instants with no sweep having run, so the
 * answers also prove AC-PLAN-10 end to end.
 */
@Import(MutableClockConfiguration.class)
class PlanGatingHttpTests extends AuthIntegrationTest {

	private static final Duration WINDOW = Duration.ofHours(336);

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private MutableClock clock;

	private enum Plan {

		TRIAL, NO_PLAN, TEAM_ACTIVE

	}

	private record Person(UUID userId, String email, Cookie cookie) {
	}

	private record Team(UUID companyId, Map<Role, Person> people, Map<Role, UUID> seats, Instant trialEnd,
			Instant readOnlyEnd) {

		Person person(Role role) {
			return people.get(role);
		}

		UUID seat(Role role) {
			return seats.get(role);
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

	private UUID newUserId() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id", UUID.class, email,
				email);
	}

	private UUID join(UUID companyId, UUID userId, String role, String status) {
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, userId, role, status);
	}

	/** A company whose trial starts now. */
	private Team team(Plan plan) throws Exception {
		return teamStartingAt(plan, clock.instant());
	}

	/**
	 * A trial that ends after {@code d} (negative: ended that long ago). Sessions last hours and are tied to the real
	 * time they were created, so the tests place the company at the position they need instead of moving the clock by days.
	 */
	private Team trialEndingIn(Duration d) throws Exception {
		return teamStartingAt(Plan.TRIAL, clock.instant().plus(d).minus(WINDOW));
	}

	private Team readOnlyTeam() throws Exception {
		return trialEndingIn(Duration.ofHours(-1));
	}

	/** In the read-only window, which ends after {@code d}. */
	private Team readOnlyEndingIn(Duration d) throws Exception {
		return teamStartingAt(Plan.TRIAL, clock.instant().plus(d).minus(WINDOW).minus(WINDOW));
	}

	private Team lockedTeam() throws Exception {
		return readOnlyEndingIn(Duration.ofHours(-1));
	}

	/** One company with an ACTIVE member in every role, all signed in, on the given plan, whose trial started at {@code start}. */
	private Team teamStartingAt(Plan plan, Instant start) throws Exception {
		Map<Role, Person> people = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			people.put(role, person());
		}
		Map<Role, UUID> seats = new EnumMap<>(Role.class);
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Gated', now(), now()) RETURNING id",
					UUID.class);
			switch (plan) {
				case TRIAL -> jdbc.update("INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, "
						+ "trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT ?, "
						+ "(SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', ?::timestamptz, "
						+ "?::timestamptz + interval '336 hours', ?::timestamptz + interval '672 hours', now(), now(), now()",
						id, start.toString(), start.toString(), start.toString());
				case NO_PLAN -> jdbc.update("INSERT INTO company_subscription (company_id, state, state_changed_at, "
						+ "created_at, updated_at) VALUES (?, 'NO_PLAN', now(), now(), now())", id);
				case TEAM_ACTIVE -> jdbc.update("INSERT INTO company_subscription (company_id, plan_id, state, "
						+ "state_changed_at, created_at, updated_at) SELECT ?, (SELECT id FROM plan WHERE code = 'TEAM' "
						+ "AND version = 1), 'ACTIVE', now(), now(), now()", id);
			}
			for (Role role : Role.values()) {
				seats.put(role, join(id, people.get(role).userId(), role.name(), "ACTIVE"));
			}
			return id;
		});
		return new Team(companyId, people, seats, start.plus(WINDOW), start.plus(WINDOW).plus(WINDOW));
	}

	private MvcResult send(Person who, HttpMethod method, String path, String body) throws Exception {
		String ip = freshIp();
		return api.send(method, path, body, who.cookie(), api.sessionCsrf(who.cookie(), ip), ip);
	}

	private MvcResult get(Person who, String path) throws Exception {
		return api.get(path, who.cookie(), freshIp());
	}

	private static String company(UUID id) {
		return "/api/v1/companies/" + id;
	}

	private static String guest() {
		return "guest." + UUID.randomUUID() + "@example.com";
	}

	private static void assertRestricted(MvcResult result, String state) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		JsonNode body = parse(result);
		assertThat(body.get("code").asString()).isEqualTo("PLAN_RESTRICTED");
		assertThat(body.get("subscriptionState").asString()).isEqualTo(state);
	}

	private static void assertForbidden(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(parse(result).get("code").asString()).isEqualTo("FORBIDDEN");
	}

	private UUID createdInvitation(Team team) throws Exception {
		MvcResult result = send(team.person(Role.OWNER), HttpMethod.POST, company(team.companyId()) + "/invitations",
				json("email", guest(), "role", "AGENT"));
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(parse(result).get("id").asString());
	}

	private int restrictedDenials(UUID companyId, String permission, String state) {
		return count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' AND company_id = ? "
				+ "AND outcome = 'denied' AND reason_code = ? AND before_value = ?", companyId,
				"plan_restricted:" + permission, state);
	}

	// ---- a running trial and an active plan: the matrix alone decides

	@Test
	void whileTheTrialRunsTheMatrixAloneDecidesAndNothingIsRestricted() throws Exception {
		Team team = team(Plan.TRIAL);

		assertThat(send(team.person(Role.OWNER), HttpMethod.POST, company(team.companyId()) + "/invitations",
				json("email", guest(), "role", "AGENT")).getResponse().getStatus()).isEqualTo(201);
		assertThat(send(team.person(Role.ADMIN), HttpMethod.PATCH,
				company(team.companyId()) + "/members/" + team.seat(Role.AGENT), json("role", "VIEWER")).getResponse()
			.getStatus()).isEqualTo(200);
		assertThat(get(team.person(Role.VIEWER), company(team.companyId())).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void anActivePlanIsNotRestricted() throws Exception {
		Team team = team(Plan.TEAM_ACTIVE);

		assertThat(send(team.person(Role.OWNER), HttpMethod.POST, company(team.companyId()) + "/invitations",
				json("email", guest(), "role", "AGENT")).getResponse().getStatus()).isEqualTo(201);
		assertThat(get(team.person(Role.VIEWER), company(team.companyId())).getResponse().getStatus()).isEqualTo(200);
	}

	// ---- EXPIRED_READ_ONLY

	@Test
	void theTrialEndInstantMakesWritesRefusedAtOnceWithNoJobHavingRun() throws Exception {
		Team team = trialEndingIn(Duration.ofMinutes(5));
		String invitations = company(team.companyId()) + "/invitations";

		clock.set(team.trialEnd().minusMillis(1));
		assertThat(send(team.person(Role.OWNER), HttpMethod.POST, invitations, json("email", guest(), "role", "AGENT"))
			.getResponse().getStatus()).isEqualTo(201);

		clock.set(team.trialEnd());
		assertRestricted(send(team.person(Role.OWNER), HttpMethod.POST, invitations,
				json("email", guest(), "role", "AGENT")), "EXPIRED_READ_ONLY");
		assertThat(jdbc.queryForObject("SELECT state FROM company_subscription WHERE company_id = ?", String.class,
				team.companyId())).as("nothing recorded the expiry").isEqualTo("TRIALING");
	}

	@Test
	void whileReadOnlyInvitingResendingRoleChangesAndReactivationAreRefused() throws Exception {
		Team team = trialEndingIn(Duration.ofMinutes(5));
		UUID pending = createdInvitation(team);
		UUID suspended = tx.execute(s -> join(team.companyId(), newUserId(), "AGENT", "SUSPENDED"));
		clock.set(team.trialEnd());
		Person owner = team.person(Role.OWNER);
		Person admin = team.person(Role.ADMIN);
		String base = company(team.companyId());

		assertRestricted(send(owner, HttpMethod.POST, base + "/invitations", json("email", guest(), "role", "AGENT")),
				"EXPIRED_READ_ONLY");
		assertRestricted(send(admin, HttpMethod.POST, base + "/invitations/" + pending + "/resend", null),
				"EXPIRED_READ_ONLY");
		assertRestricted(send(admin, HttpMethod.PATCH, base + "/members/" + team.seat(Role.AGENT), json("role", "VIEWER")),
				"EXPIRED_READ_ONLY");
		assertRestricted(send(admin, HttpMethod.POST, base + "/members/" + suspended + "/reactivate", null),
				"EXPIRED_READ_ONLY");
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, team.seat(Role.AGENT)))
			.isEqualTo("AGENT");
	}

	@Test
	void whileReadOnlyRevokingSuspendingRemovingAndLeavingAreAllowedAndReadsContinue() throws Exception {
		Team team = trialEndingIn(Duration.ofMinutes(5));
		UUID pending = createdInvitation(team);
		clock.set(team.trialEnd());
		Person owner = team.person(Role.OWNER);
		String base = company(team.companyId());

		assertThat(send(owner, HttpMethod.DELETE, base + "/invitations/" + pending, null).getResponse().getStatus())
			.isEqualTo(204);
		assertThat(send(owner, HttpMethod.POST, base + "/members/" + team.seat(Role.AGENT) + "/suspend", null)
			.getResponse().getStatus()).isEqualTo(200);
		assertThat(send(owner, HttpMethod.DELETE, base + "/members/" + team.seat(Role.AGENT), null).getResponse()
			.getStatus()).isEqualTo(204);
		assertThat(send(team.person(Role.VIEWER), HttpMethod.POST, base + "/leave", null).getResponse().getStatus())
			.isEqualTo(204);
		assertThat(get(team.person(Role.ADMIN), base + "/members").getResponse().getStatus()).isEqualTo(200);
		assertThat(get(owner, base).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void aRoleThatNeverHoldsThePermissionIsToldForbiddenNotRestricted() throws Exception {
		Team team = readOnlyTeam();

		assertForbidden(send(team.person(Role.AGENT), HttpMethod.POST, company(team.companyId()) + "/invitations",
				json("email", guest(), "role", "AGENT")));
		assertForbidden(send(team.person(Role.VIEWER), HttpMethod.PATCH,
				company(team.companyId()) + "/members/" + team.seat(Role.AGENT), json("role", "VIEWER")));
	}

	// ---- NO_PLAN

	@Test
	void aCompanyWithNoPlanCanBeReadButNotInvitedToOrChanged() throws Exception {
		Team team = team(Plan.NO_PLAN);
		Person owner = team.person(Role.OWNER);
		String base = company(team.companyId());

		assertThat(get(owner, base).getResponse().getStatus()).isEqualTo(200);
		assertThat(get(team.person(Role.ADMIN), base + "/members").getResponse().getStatus()).isEqualTo(200);
		assertRestricted(send(owner, HttpMethod.POST, base + "/invitations", json("email", guest(), "role", "AGENT")),
				"NO_PLAN");
		assertRestricted(send(owner, HttpMethod.PATCH, base + "/members/" + team.seat(Role.AGENT), json("role", "VIEWER")),
				"NO_PLAN");
		assertThat(send(owner, HttpMethod.POST, base + "/members/" + team.seat(Role.AGENT) + "/suspend", null)
			.getResponse().getStatus()).isEqualTo(200);
	}

	// ---- EXPIRED_LOCKED

	@Test
	void afterTheReadOnlyWindowOnlyTheOwnerReadsAndNothingElseIsAllowed() throws Exception {
		Team team = lockedTeam();
		// An invitation that does not exist: the state is checked before the invitation is looked up.
		UUID pending = UUID.randomUUID();
		Person owner = team.person(Role.OWNER);
		String base = company(team.companyId());

		assertThat(get(owner, base).getResponse().getStatus()).isEqualTo(200);
		assertThat(get(owner, base + "/members").getResponse().getStatus()).isEqualTo(200);
		assertRestricted(get(team.person(Role.ADMIN), base + "/members"), "EXPIRED_LOCKED");
		assertRestricted(get(team.person(Role.VIEWER), base), "EXPIRED_LOCKED");
		assertRestricted(get(team.person(Role.AGENT), base), "EXPIRED_LOCKED");

		assertRestricted(send(owner, HttpMethod.DELETE, base + "/invitations/" + pending, null), "EXPIRED_LOCKED");
		assertRestricted(send(owner, HttpMethod.POST, base + "/members/" + team.seat(Role.AGENT) + "/suspend", null),
				"EXPIRED_LOCKED");
		assertRestricted(send(team.person(Role.AGENT), HttpMethod.POST, base + "/leave", null), "EXPIRED_LOCKED");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class,
				team.seat(Role.AGENT))).isEqualTo("ACTIVE");
	}

	@Test
	void theLastInstantOfTheReadOnlyWindowIsStillReadOnly() throws Exception {
		Team team = readOnlyEndingIn(Duration.ofMinutes(5));
		clock.set(team.readOnlyEnd().minusMillis(1));

		assertThat(get(team.person(Role.VIEWER), company(team.companyId())).getResponse().getStatus()).isEqualTo(200);
		clock.set(team.readOnlyEnd());
		assertRestricted(get(team.person(Role.VIEWER), company(team.companyId())), "EXPIRED_LOCKED");
	}

	// ---- the denial audit

	@Test
	void aRefusalBecauseOfTheStateIsAuditedLikeAPermissionDenialOncePerWindow() throws Exception {
		Team team = readOnlyTeam();
		String path = company(team.companyId()) + "/invitations";

		for (int i = 0; i < 3; i++) {
			assertRestricted(send(team.person(Role.OWNER), HttpMethod.POST, path,
					json("email", guest(), "role", "AGENT")), "EXPIRED_READ_ONLY");
		}

		assertThat(restrictedDenials(team.companyId(), "invite_members", "EXPIRED_READ_ONLY")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_event WHERE event_type = "
				+ "'tenant.authorization.denied' AND company_id = ? AND reason_code = 'plan_restricted:invite_members'",
				UUID.class, team.companyId())).isEqualTo(team.person(Role.OWNER).userId());
	}

	// ---- order of the checks

	@Test
	void aRestrictedRequestIsRefusedBeforeAnyLockSoItNeverQueuesForTheCompany() throws Exception {
		Team team = readOnlyTeam();
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
			jdbc.queryForObject("SELECT id FROM company WHERE id = ? FOR UPDATE", UUID.class, team.companyId());
			holding.countDown();
			try {
				release.await(20, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		try {
			assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
			long started = System.nanoTime();

			MvcResult result = send(team.person(Role.ADMIN), HttpMethod.PATCH,
					company(team.companyId()) + "/members/" + team.seat(Role.AGENT), json("role", "VIEWER"));
			long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();

			assertRestricted(result, "EXPIRED_READ_ONLY");
			// The company lock waits up to 5 seconds (app.tenant.lock-timeout); a refusal before the lock does not wait.
			assertThat(millis).as("the refusal did not queue for the held company lock").isLessThan(4_500);
		}
		finally {
			release.countDown();
			holder.get(30, TimeUnit.SECONDS);
		}
	}

	// ---- the subscription endpoint

	@Test
	void anOwnerAndAnAdminSeeThePlanAndAnAgentAndAViewerDoNot() throws Exception {
		Team team = team(Plan.TRIAL);
		String path = company(team.companyId()) + "/subscription";

		MvcResult asOwner = get(team.person(Role.OWNER), path);
		JsonNode body = parse(asOwner);

		assertThat(asOwner.getResponse().getStatus()).isEqualTo(200);
		assertThat(body.get("plan").get("code").asString()).isEqualTo("TRIAL");
		assertThat(body.get("plan").get("name").asString()).isEqualTo("Trial");
		assertThat(body.get("plan").get("version").asInt()).isEqualTo(1);
		assertThat(body.get("state").asString()).isEqualTo("TRIALING");
		assertThat(Instant.parse(body.get("trialEndsAt").asString())).isCloseTo(team.trialEnd(),
				org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
		assertThat(body.get("readOnlyEndsAt").asString()).isNotBlank();
		assertThat(body.get("features").size()).isEqualTo(8);
		assertThat(body.get("features").findValuesAsString("")).isNotNull();
		JsonNode members = null;
		for (JsonNode limit : body.get("limits")) {
			if (limit.get("key").asString().equals("ACTIVE_MEMBERS")) {
				members = limit;
			}
			else {
				assertThat(limit.get("enforced").asBoolean()).as(limit.get("key").asString()).isFalse();
				assertThat(limit.get("usage").isNull()).isTrue();
			}
		}
		assertThat(members).isNotNull();
		assertThat(members.get("value").asInt()).isEqualTo(15);
		assertThat(members.get("enforced").asBoolean()).isTrue();
		assertThat(members.get("usage").asInt()).isEqualTo(4);
		assertThat(get(team.person(Role.ADMIN), path).getResponse().getStatus()).isEqualTo(200);
		assertForbidden(get(team.person(Role.AGENT), path));
		assertForbidden(get(team.person(Role.VIEWER), path));
	}

	@Test
	void theEndedStatesAreShownToTheOwnerAndAnAdminIsRestrictedOnceLocked() throws Exception {
		Team readOnly = readOnlyTeam();
		Team locked = lockedTeam();

		assertThat(parse(get(readOnly.person(Role.ADMIN), company(readOnly.companyId()) + "/subscription")).get("state")
			.asString()).isEqualTo("EXPIRED_READ_ONLY");
		String path = company(locked.companyId()) + "/subscription";
		assertThat(parse(get(locked.person(Role.OWNER), path)).get("state").asString()).isEqualTo("EXPIRED_LOCKED");
		assertRestricted(get(locked.person(Role.ADMIN), path), "EXPIRED_LOCKED");
	}

	@Test
	void aCompanyWithNoPlanShowsNoPlanAndNoLimits() throws Exception {
		Team team = team(Plan.NO_PLAN);

		JsonNode body = parse(get(team.person(Role.OWNER), company(team.companyId()) + "/subscription"));

		assertThat(body.get("state").asString()).isEqualTo("NO_PLAN");
		assertThat(body.get("plan").isNull()).isTrue();
		assertThat(body.get("features").size()).isZero();
		assertThat(body.get("limits").size()).isZero();
		assertThat(body.get("trialEndsAt").isNull()).isTrue();
	}

	@Test
	void anActivePlanShowsItsOwnLimitsAndNoTrialInstants() throws Exception {
		Team team = team(Plan.TEAM_ACTIVE);

		JsonNode body = parse(get(team.person(Role.OWNER), company(team.companyId()) + "/subscription"));

		assertThat(body.get("state").asString()).isEqualTo("ACTIVE");
		assertThat(body.get("plan").get("code").asString()).isEqualTo("TEAM");
		assertThat(body.get("trialEndsAt").isNull()).isTrue();
		assertThat(List.of(body.get("limits").get(0).get("key").asString(), body.get("limits").get(1).get("key").asString(),
				body.get("limits").get(2).get("key").asString())).containsExactlyInAnyOrder("TEAMS", "ACTIVE_MEMBERS",
						"ACTIVE_ASSESSMENTS");
	}

	@Test
	void anotherCompanysMembersAndOutsidersCannotReadThePlan() throws Exception {
		Team mine = team(Plan.TRIAL);
		Team other = team(Plan.TRIAL);
		Person outsider = person();

		// Even an Owner of another company gets "not found", exactly like a stranger (AC-PLAN-07).
		assertThat(get(other.person(Role.OWNER), company(mine.companyId()) + "/subscription").getResponse().getStatus())
			.isEqualTo(404);
		assertThat(get(outsider, company(mine.companyId()) + "/subscription").getResponse().getStatus()).isEqualTo(404);
		assertThat(get(mine.person(Role.OWNER), company(UUID.randomUUID()) + "/subscription").getResponse().getStatus())
			.isEqualTo(404);
		assertThat(get(mine.person(Role.OWNER), company(other.companyId()) + "/subscription").getResponse().getStatus())
			.isEqualTo(404);
	}

	@Test
	void noRouteChangesAPlanAndAClientSuppliedPlanIsIgnored() throws Exception {
		Team team = trialEndingIn(Duration.ofMinutes(5));
		String path = company(team.companyId()) + "/subscription";
		Person owner = team.person(Role.OWNER);

		for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
			int status = send(owner, method, path, json("plan", "TEAM", "state", "ACTIVE")).getResponse().getStatus();
			// No route exists: the security rules deny what they do not list (403), or routing finds none (404, 405).
			assertThat(status).as(method.name()).isIn(403, 404, 405);
		}
		clock.set(team.trialEnd());
		// Plan and state fields smuggled into a request are not read: the server rejects unknown fields outright, and a
		// request with only known fields is still decided by the stored state (AC-PLAN-03).
		assertThat(send(owner, HttpMethod.POST, company(team.companyId()) + "/invitations", "{\"email\":\"" + guest()
				+ "\",\"role\":\"AGENT\",\"plan\":\"TEAM\",\"subscriptionState\":\"ACTIVE\"}").getResponse()
			.getStatus()).isEqualTo(400);
		assertRestricted(send(owner, HttpMethod.POST, company(team.companyId()) + "/invitations",
				json("email", guest(), "role", "AGENT")), "EXPIRED_READ_ONLY");
		assertThat(jdbc.queryForObject("SELECT state FROM company_subscription WHERE company_id = ?", String.class,
				team.companyId())).isEqualTo("TRIALING");
		assertThat(jdbc.queryForObject("SELECT p.code FROM company_subscription s JOIN plan p ON p.id = s.plan_id WHERE "
				+ "s.company_id = ?", String.class, team.companyId())).isEqualTo("TRIAL");
	}

	// ---- the member limit over HTTP

	@Test
	void reactivatingAtTheLimitIsRefusedWithTheMemberLimitCode() throws Exception {
		Team team = team(Plan.TRIAL);
		for (int i = 0; i < 11; i++) {
			tx.execute(s -> join(team.companyId(), newUserId(), "AGENT", "ACTIVE"));
		}
		UUID suspended = tx.execute(s -> join(team.companyId(), newUserId(), "AGENT", "SUSPENDED"));

		MvcResult result = send(team.person(Role.OWNER), HttpMethod.POST,
				company(team.companyId()) + "/members/" + suspended + "/reactivate", null);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(parse(result).get("code").asString()).isEqualTo("MEMBER_LIMIT_REACHED");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, suspended))
			.isEqualTo("SUSPENDED");
	}

}
