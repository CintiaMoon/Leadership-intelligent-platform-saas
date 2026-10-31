package com.example.saas.api.tenant.deletion;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import com.example.saas.api.MutableClock;
import com.example.saas.api.MutableClockConfiguration;
import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.TestLifecycle;
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
 * The company deletion endpoints over HTTP, against PostgreSQL (SPEC-DEL, Phase 3): AC-DEL-01, 10, 13, 17, 20, 28 and the
 * tenant boundary, with the later states of the lifecycle (AC-DEL-29).
 */
@Import(MutableClockConfiguration.class)
class DeletionHttpTests extends AuthIntegrationTest {

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

	/** One company with a signed-in member in every role. */
	private Team team() throws Exception {
		Map<Role, Person> people = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			people.put(role, person());
		}
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Closing Co', now(), now()) RETURNING id",
					UUID.class);
			for (Role role : Role.values()) {
				jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
						+ "VALUES (?, ?, ?, 'ACTIVE', now(), now())", id, people.get(role).userId(), role.name());
			}
			return id;
		});
		return new Team(companyId, people);
	}

	private MvcResult send(Person who, HttpMethod method, String path) throws Exception {
		String ip = freshIp();
		return api.send(method, path, null, who.cookie(), api.sessionCsrf(who.cookie(), ip), ip);
	}

	private static String deletion(UUID companyId) {
		return "/api/v1/companies/" + companyId + "/deletion";
	}

	private static String company(UUID companyId) {
		return "/api/v1/companies/" + companyId;
	}

	private MvcResult request(Person who, UUID companyId) throws Exception {
		return send(who, HttpMethod.POST, deletion(companyId));
	}

	private MvcResult cancel(Person who, UUID companyId) throws Exception {
		return send(who, HttpMethod.DELETE, deletion(companyId));
	}

	private int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private String code(MvcResult result) throws Exception {
		return parse(result).get("code").asString();
	}

	private String statusOf(UUID companyId) {
		return jdbc.queryForObject("SELECT status FROM company WHERE id = ?", String.class, companyId);
	}

	/** A step-up replaces the session identifier: the person carries on with the new cookie. */
	private Person stepUp(Person person) throws Exception {
		String ip = freshIp();
		MvcResult ok = api.send(HttpMethod.POST, "/api/v1/auth/step-up", json("password", STRONG_PASSWORD), person.cookie(),
				api.sessionCsrf(person.cookie(), ip), ip);
		assertThat(status(ok)).isEqualTo(204);
		return new Person(person.userId(), person.email(), sessionCookie(ok));
	}

	// ---- requesting

	@Test
	void theOwnerRequestsAndTheResponseHasExactlyTheDocumentedFieldsAndNoAddressOrUserId() throws Exception {
		Team team = team();

		MvcResult result = request(team.person(Role.OWNER), team.companyId());

		assertThat(status(result)).isEqualTo(200);
		JsonNode body = parse(result);
		assertThat(StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(body.propertyNames().iterator(), 0), false)
			.toList()).containsExactlyInAnyOrderElementsOf(Set.of("status", "requestedAt", "purgeAt"));
		assertThat(body.get("status").asString()).isEqualTo("PENDING_DELETION");
		assertThat(Duration.between(Instant.parse(body.get("requestedAt").asString()), Instant.parse(body.get("purgeAt").asString())))
			.isEqualTo(Duration.ofDays(30));
		String text = result.getResponse().getContentAsString();
		assertThat(text).doesNotContain("@", team.person(Role.OWNER).userId().toString());
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
	}

	@Test
	void everyRoleOverTheRealEndpointMatchesTheMatrixAndTheDenialIsAudited() throws Exception {
		Team team = team();
		Person outsider = person();

		for (Role role : List.of(Role.ADMIN, Role.AGENT, Role.VIEWER)) {
			MvcResult refused = request(team.person(role), team.companyId());
			assertThat(status(refused)).as("%s requests", role).isEqualTo(403);
			assertThat(code(refused)).isEqualTo("FORBIDDEN");
		}
		assertThat(status(request(outsider, team.companyId()))).as("not a member").isEqualTo(404);
		MvcResult anonymous = api.send(HttpMethod.POST, deletion(team.companyId()), null, null, null, freshIp());
		assertThat(status(anonymous)).as("not signed in: refused before anything else, by the CSRF check").isEqualTo(403);
		assertThat(code(anonymous)).isEqualTo("CSRF_REJECTED");
		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' "
				+ "AND company_id = ? AND reason_code = 'request_company_deletion'", team.companyId()))
			.as("the three refused members are audited, the outsider is not").isEqualTo(3);

		assertThat(status(request(team.person(Role.OWNER), team.companyId()))).isEqualTo(200);
	}

	@Test
	void theCsrfTokenIsRequiredOnBothChangingOperations() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);

		MvcResult noCsrf = api.send(HttpMethod.POST, deletion(team.companyId()), null, owner.cookie(), null, freshIp());
		assertThat(status(noCsrf)).isEqualTo(403);
		assertThat(code(noCsrf)).isEqualTo("CSRF_REJECTED");
		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");

		assertThat(status(request(owner, team.companyId()))).isEqualTo(200);
		MvcResult noCsrfCancel = api.send(HttpMethod.DELETE, deletion(team.companyId()), null, owner.cookie(), null, freshIp());
		assertThat(status(noCsrfCancel)).isEqualTo(403);
		assertThat(code(noCsrfCancel)).isEqualTo("CSRF_REJECTED");
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
	}

	@Test
	void aRequestNeedsARecentLoginAndAStepUpOpensTheWayAgainButACancellationNeedsNone() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		clock.advance(Duration.ofMinutes(11));

		MvcResult stale = request(owner, team.companyId());
		assertThat(status(stale)).isEqualTo(403);
		assertThat(code(stale)).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(statusOf(team.companyId())).isEqualTo("ACTIVE");

		owner = stepUp(owner);
		assertThat(status(request(owner, team.companyId()))).isEqualTo(200);

		clock.advance(Duration.ofMinutes(11));
		MvcResult cancelled = cancel(owner, team.companyId());
		assertThat(status(cancelled)).as("no recent login needed to cancel").isEqualTo(200);
		assertThat(parse(cancelled).get("status").asString()).isEqualTo("ACTIVE");
	}

	@Test
	void aFlaggedCompanyAndANotPendingCompanyAreConflictsAndASecondRequestIsRefused() throws Exception {
		Team flagged = team();
		jdbc.update("UPDATE company SET ownership_resolution_at = now() WHERE id = ?", flagged.companyId());
		MvcResult refused = request(flagged.person(Role.OWNER), flagged.companyId());
		assertThat(status(refused)).isEqualTo(409);
		assertThat(code(refused)).isEqualTo("CONFLICT");

		Team team = team();
		Person owner = team.person(Role.OWNER);
		assertThat(status(cancel(owner, team.companyId()))).as("nothing is pending").isEqualTo(409);
		assertThat(status(request(owner, team.companyId()))).isEqualTo(200);
		MvcResult again = request(owner, team.companyId());
		assertThat(status(again)).as("the policy closes the company to every change but the cancellation").isEqualTo(403);
		assertThat(code(again)).isEqualTo("FORBIDDEN");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'company.deletion.requested' AND company_id = ?",
				team.companyId())).isEqualTo(1);
	}

	@Test
	void theSixthOperationInAnHourIs429WithRetryAfterAndChangesNothing() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		for (int i = 0; i < 2; i++) {
			assertThat(status(request(owner, team.companyId()))).isEqualTo(200);
			assertThat(status(cancel(owner, team.companyId()))).isEqualTo(200);
		}
		assertThat(status(request(owner, team.companyId()))).isEqualTo(200);

		MvcResult limited = cancel(owner, team.companyId());

		assertThat(status(limited)).isEqualTo(429);
		assertThat(code(limited)).isEqualTo("RATE_LIMITED");
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");
	}

	// ---- cancelling

	@Test
	void onlyTheOwnerCancelsAndACancellationAfterTheDeadlineIsAConflict() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		assertThat(status(request(owner, team.companyId()))).isEqualTo(200);

		for (Role role : List.of(Role.ADMIN, Role.AGENT, Role.VIEWER)) {
			assertThat(status(cancel(team.person(role), team.companyId()))).as("%s cancels", role).isEqualTo(403);
		}
		assertThat(status(cancel(person(), team.companyId()))).as("not a member").isEqualTo(404);
		assertThat(statusOf(team.companyId())).isEqualTo("PENDING_DELETION");

		Team late = team();
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION', deletion_requested_at = now() - interval '31 days', "
				+ "purge_at = now() - interval '1 day', deletion_requested_by = ? WHERE id = ?",
				late.person(Role.OWNER).userId(), late.companyId());
		MvcResult tooLate = cancel(late.person(Role.OWNER), late.companyId());
		assertThat(status(tooLate)).isEqualTo(409);
		assertThat(code(tooLate)).isEqualTo("CONFLICT");
		assertThat(statusOf(late.companyId())).isEqualTo("PENDING_DELETION");

		MvcResult cancelled = cancel(owner, team.companyId());
		assertThat(status(cancelled)).isEqualTo(200);
		JsonNode body = parse(cancelled);
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(body.get("requestedAt").isNull()).isTrue();
		assertThat(body.get("purgeAt").isNull()).isTrue();
	}

	@Test
	void anOwnerOfAnotherCompanyCannotRequestOrCancelThisOne() throws Exception {
		Team victim = team();
		Team other = team();
		Person stranger = other.person(Role.OWNER);

		assertThat(status(request(stranger, victim.companyId()))).isEqualTo(404);
		assertThat(status(request(victim.person(Role.OWNER), victim.companyId()))).isEqualTo(200);
		assertThat(status(cancel(stranger, victim.companyId()))).isEqualTo(404);

		assertThat(statusOf(victim.companyId())).isEqualTo("PENDING_DELETION");
		assertThat(statusOf(other.companyId())).isEqualTo("ACTIVE");
	}

	@Test
	void aMalformedCompanyIdIsNotFound() throws Exception {
		Person owner = person();
		assertThat(status(send(owner, HttpMethod.POST, "/api/v1/companies/not-a-uuid/deletion"))).isEqualTo(404);
		assertThat(status(send(owner, HttpMethod.DELETE, "/api/v1/companies/not-a-uuid/deletion"))).isEqualTo(404);
	}

	// ---- what the rest of the API shows

	@Test
	void theCompanyDetailsCarryTheDeletionRequestForTheOwnerAndNobodyElseCanReadThemWhilePending() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		assertThat(status(request(owner, team.companyId()))).isEqualTo(200);

		MvcResult details = api.get(company(team.companyId()), owner.cookie(), freshIp());
		assertThat(status(details)).isEqualTo(200);
		JsonNode body = parse(details);
		assertThat(body.get("status").asString()).isEqualTo("PENDING_DELETION");
		assertThat(body.get("deletionRequestedAt").asString()).isNotBlank();
		assertThat(body.get("purgeAt").asString()).isNotBlank();
		assertThat(status(api.get(company(team.companyId()), team.person(Role.ADMIN).cookie(), freshIp())))
			.as("an Admin cannot read a company that is pending deletion").isEqualTo(403);
		assertThat(parse(api.get("/api/v1/companies", owner.cookie(), freshIp())).findValuesAsString("id"))
			.contains(team.companyId().toString());
		assertThat(parse(api.get("/api/v1/companies", team.person(Role.ADMIN).cookie(), freshIp())).findValuesAsString("id"))
			.doesNotContain(team.companyId().toString());

		assertThat(status(cancel(owner, team.companyId()))).isEqualTo(200);
		JsonNode after = parse(api.get(company(team.companyId()), owner.cookie(), freshIp()));
		assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(after.get("deletionRequestedAt").isNull()).isTrue();
		assertThat(after.get("purgeAt").isNull()).isTrue();
	}

	@Test
	void onceThePurgeIsQueuedNobodyCanDoAnythingAndTheOwnerCanOnlyReadTheCompanyDetails() throws Exception {
		for (String state : List.of("PURGE_QUEUED", "PURGING", "PURGE_FAILED")) {
			Team team = team();
			TestLifecycle.markPendingDeletion(jdbc, team.companyId());
			moveToPurgeState(team.companyId(), state);
			Person owner = team.person(Role.OWNER);

			MvcResult details = api.get(company(team.companyId()), owner.cookie(), freshIp());
			assertThat(status(details)).as(state).isEqualTo(200);
			assertThat(parse(details).get("status").asString()).isEqualTo(state);
			assertThat(status(request(owner, team.companyId()))).as("%s: request", state).isEqualTo(403);
			assertThat(status(cancel(owner, team.companyId()))).as("%s: cancel", state).isEqualTo(403);
			assertThat(status(api.get("/api/v1/companies/" + team.companyId() + "/members", owner.cookie(), freshIp())))
				.as("%s: the Owner cannot list members", state).isEqualTo(403);
			assertThat(status(api.get(company(team.companyId()), team.person(Role.ADMIN).cookie(), freshIp())))
				.as("%s: an Admin", state).isEqualTo(403);
			assertThat(statusOf(team.companyId())).isEqualTo(state);
		}
	}

	private void moveToPurgeState(UUID companyId, String state) {
		jdbc.update("UPDATE company SET status = 'PURGE_QUEUED' WHERE id = ?", companyId);
		if (!state.equals("PURGE_QUEUED")) {
			jdbc.update("UPDATE company SET status = 'PURGING', purge_claimed_until = now() + interval '5 minutes' WHERE id = ?",
					companyId);
		}
		if (state.equals("PURGE_FAILED")) {
			jdbc.update("UPDATE company SET status = 'PURGE_FAILED', purge_claimed_until = NULL, "
					+ "purge_error_code = 'HANDLER_FAILED', purge_attempts = 1 WHERE id = ?", companyId);
		}
	}

}
