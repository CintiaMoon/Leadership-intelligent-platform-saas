package com.example.saas.api.tenant.ownership;

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

/** The ownership transfer endpoints over HTTP, against PostgreSQL (SPEC-OWN, Phase 3). */
@Import(MutableClockConfiguration.class)
class OwnershipTransferApiTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private MutableClock clock;

	private record Person(UUID userId, String email, Cookie cookie) {
	}

	private record Team(UUID companyId, Map<Role, Person> people, Map<Role, UUID> seats) {

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

	/** One company with a signed-in member in every role. */
	private Team team() throws Exception {
		Map<Role, Person> people = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			people.put(role, person());
		}
		Map<Role, UUID> seats = new EnumMap<>(Role.class);
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Handover', now(), now()) RETURNING id",
					UUID.class);
			for (Role role : Role.values()) {
				seats.put(role, jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, "
						+ "updated_at) VALUES (?, ?, ?, 'ACTIVE', now(), now()) RETURNING id", UUID.class, id,
						people.get(role).userId(), role.name()));
			}
			return id;
		});
		return new Team(companyId, people, seats);
	}

	private MvcResult send(Person who, HttpMethod method, String path, String body) throws Exception {
		String ip = freshIp();
		return api.send(method, path, body, who.cookie(), api.sessionCsrf(who.cookie(), ip), ip);
	}

	private static String transfers(UUID companyId) {
		return "/api/v1/companies/" + companyId + "/ownership-transfers";
	}

	private MvcResult initiate(Person who, UUID companyId, UUID recipientSeat) throws Exception {
		return send(who, HttpMethod.POST, transfers(companyId), json("recipientMembershipId", recipientSeat.toString()));
	}

	private UUID started(Team team, Role recipient) throws Exception {
		MvcResult result = initiate(team.person(Role.OWNER), team.companyId(), team.seat(recipient));
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(parse(result).get("id").asString());
	}

	/**
	 * Makes a pending transfer overdue by moving its own timestamps back. Moving the test clock by days would also end
	 * every session (a session's age is measured from its real creation time), so the readers could no longer sign in.
	 */
	private void makeOverdue(UUID transferId) {
		jdbc.update("UPDATE ownership_transfer SET created_at = created_at - interval '8 days', "
				+ "expires_at = expires_at - interval '8 days' WHERE id = ?", transferId);
	}

	/** A step-up replaces the session identifier: the person carries on with the new cookie. */
	private Person stepUp(Person person) throws Exception {
		MvcResult result = send(person, HttpMethod.POST, "/api/v1/auth/step-up", json("password", STRONG_PASSWORD));
		assertThat(status(result)).isEqualTo(204);
		return new Person(person.userId(), person.email(), sessionCookie(result));
	}

	private int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private String code(MvcResult result) throws Exception {
		return parse(result).get("code").asString();
	}

	// ---- initiating

	@Test
	void theOwnerInitiatesAndTheResponseHasExactlyTheDocumentedFieldsAndNoAddressOrUserId() throws Exception {
		Team team = team();

		MvcResult result = initiate(team.person(Role.OWNER), team.companyId(), team.seat(Role.ADMIN));

		assertThat(status(result)).isEqualTo(201);
		JsonNode body = parse(result);
		assertThat(StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(body.propertyNames().iterator(), 0), false)
			.toList()).containsExactlyInAnyOrderElementsOf(Set.of("id", "status", "initiatorName", "recipientName",
					"youAreInitiator", "youAreRecipient", "createdAt", "expiresAt"));
		assertThat(body.get("status").asString()).isEqualTo("PENDING");
		assertThat(body.get("youAreInitiator").asBoolean()).isTrue();
		assertThat(body.get("youAreRecipient").asBoolean()).isFalse();
		assertThat(Duration.between(Instant.now(), Instant.parse(body.get("expiresAt").asString())))
			.isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1));
		String text = result.getResponse().getContentAsString();
		assertThat(text).doesNotContain("@", team.person(Role.ADMIN).userId().toString(), team.person(Role.OWNER).userId().toString());
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

	@Test
	void everyRoleOverTheRealEndpointMatchesTheMatrixAndOutsidersAndAnonymousCallersAreRefused() throws Exception {
		Team team = team();
		Person outsider = person();

		for (Role role : List.of(Role.ADMIN, Role.AGENT, Role.VIEWER)) {
			MvcResult refused = initiate(team.person(role), team.companyId(), team.seat(Role.AGENT));
			assertThat(status(refused)).as("%s initiates", role).isEqualTo(403);
			assertThat(code(refused)).isEqualTo("FORBIDDEN");
		}
		assertThat(status(initiate(outsider, team.companyId(), team.seat(Role.ADMIN)))).as("not a member").isEqualTo(404);
		assertThat(api.send(HttpMethod.GET, "/api/v1/companies/" + team.companyId() + "/ownership-transfer", null, null, null,
				freshIp()).getResponse().getStatus()).as("not signed in").isEqualTo(401);
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();

		assertThat(status(initiate(team.person(Role.OWNER), team.companyId(), team.seat(Role.ADMIN)))).isEqualTo(201);
	}

	@Test
	void theCsrfTokenIsRequiredOnEveryChangingOperation() throws Exception {
		Team team = team();
		UUID id = started(team, Role.ADMIN);
		Person owner = team.person(Role.OWNER);
		Person admin = team.person(Role.ADMIN);

		for (MvcResult noCsrf : List.of(
				api.send(HttpMethod.POST, transfers(team.companyId()), json("recipientMembershipId", team.seat(Role.AGENT).toString()),
						owner.cookie(), null, freshIp()),
				api.send(HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/accept", null, admin.cookie(), null, freshIp()),
				api.send(HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/cancel", null, owner.cookie(), null, freshIp()))) {
			assertThat(status(noCsrf)).isEqualTo(403);
			assertThat(code(noCsrf)).isEqualTo("CSRF_REJECTED");
		}
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, id)).isEqualTo("PENDING");
	}

	@Test
	void aBadRecipientIsABadRequestANotFoundOrAConflictAccordingToWhatIsWrong() throws Exception {
		Team team = team();
		Team other = team();
		Person owner = team.person(Role.OWNER);
		String path = transfers(team.companyId());

		assertThat(status(send(owner, HttpMethod.POST, path, json("recipientMembershipId", "not-a-uuid")))).as("malformed").isEqualTo(400);
		MvcResult missing = send(owner, HttpMethod.POST, path, "{}");
		assertThat(status(missing)).as("missing").isEqualTo(400);
		assertThat(parse(missing).get("errors").get(0).get("code").asString()).isEqualTo("REQUIRED");
		assertThat(status(send(owner, HttpMethod.POST, path, "{\"recipientMembershipId\":\"" + team.seat(Role.ADMIN) + "\",\"role\":\"OWNER\"}")))
			.as("unknown field").isEqualTo(400);
		assertThat(status(initiate(owner, team.companyId(), team.seat(Role.OWNER)))).as("oneself").isEqualTo(400);
		assertThat(status(initiate(owner, team.companyId(), other.seat(Role.ADMIN)))).as("another company's member").isEqualTo(404);
		assertThat(status(initiate(owner, team.companyId(), UUID.randomUUID()))).as("unknown membership").isEqualTo(404);
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.seat(Role.AGENT));
		MvcResult suspended = initiate(owner, team.companyId(), team.seat(Role.AGENT));
		assertThat(status(suspended)).as("suspended").isEqualTo(409);
		assertThat(code(suspended)).isEqualTo("CONFLICT");
		assertThat(status(send(owner, HttpMethod.POST, "/api/v1/companies/not-a-uuid/ownership-transfers",
				json("recipientMembershipId", team.seat(Role.ADMIN).toString())))).as("malformed company").isEqualTo(404);
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();
	}

	@Test
	void aSecondPendingTransferIs409AndTheSixthInADayIs429WithRetryAfter() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		UUID first = started(team, Role.ADMIN);
		assertThat(status(initiate(owner, team.companyId(), team.seat(Role.AGENT)))).isEqualTo(409);

		assertThat(status(send(owner, HttpMethod.POST, transfers(team.companyId()) + "/" + first + "/cancel", null))).isEqualTo(200);
		for (int i = 0; i < 4; i++) {
			UUID id = started(team, Role.ADMIN);
			assertThat(status(send(owner, HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/cancel", null))).isEqualTo(200);
		}

		MvcResult limited = initiate(owner, team.companyId(), team.seat(Role.ADMIN));
		assertThat(status(limited)).isEqualTo(429);
		assertThat(code(limited)).isEqualTo("RATE_LIMITED");
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
	}

	@Test
	void noOwnershipOperationIsAllowedWhileTheCompanyIsPendingDeletion() throws Exception {
		Team team = team();
		UUID id = started(team, Role.ADMIN);
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", team.companyId());

		assertThat(status(initiate(team.person(Role.OWNER), team.companyId(), team.seat(Role.AGENT)))).isEqualTo(403);
		assertThat(status(send(team.person(Role.OWNER), HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/cancel", null)))
			.isEqualTo(403);
		MvcResult accept = send(team.person(Role.ADMIN), HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/accept", null);
		assertThat(status(accept)).isEqualTo(409);
	}

	// ---- recent authentication (step-up)

	@Test
	void initiatingAndAcceptingNeedARecentLoginAndAStepUpOpensTheWayAgain() throws Exception {
		Team team = team();
		Person owner = team.person(Role.OWNER);
		clock.advance(Duration.ofMinutes(11));

		MvcResult stale = initiate(owner, team.companyId(), team.seat(Role.ADMIN));
		assertThat(status(stale)).isEqualTo(403);
		assertThat(code(stale)).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();

		owner = stepUp(owner);
		MvcResult created = initiate(owner, team.companyId(), team.seat(Role.ADMIN));
		assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
		UUID id = UUID.fromString(parse(created).get("id").asString());

		clock.advance(Duration.ofMinutes(11));
		Person admin = team.person(Role.ADMIN);
		MvcResult staleAccept = send(admin, HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/accept", null);
		assertThat(status(staleAccept)).isEqualTo(403);
		assertThat(code(staleAccept)).isEqualTo("REAUTHENTICATION_REQUIRED");
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, id)).isEqualTo("PENDING");

		admin = stepUp(admin);
		assertThat(status(send(admin, HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/accept", null))).isEqualTo(200);
	}

	// ---- reading (AC-OWN-22)

	@Test
	void onlyTheTwoPartiesCanReadATransferAndEveryoneElseGets404() throws Exception {
		Team team = team();
		Team other = team();
		UUID id = started(team, Role.ADMIN);
		String current = "/api/v1/companies/" + team.companyId() + "/ownership-transfer";
		String one = transfers(team.companyId()) + "/" + id;

		for (Person party : List.of(team.person(Role.OWNER), team.person(Role.ADMIN))) {
			assertThat(status(send(party, HttpMethod.GET, current, null))).isEqualTo(200);
			assertThat(status(send(party, HttpMethod.GET, one, null))).isEqualTo(200);
		}
		assertThat(parse(send(team.person(Role.ADMIN), HttpMethod.GET, current, null)).get("youAreRecipient").asBoolean()).isTrue();
		for (Person bystander : List.of(team.person(Role.AGENT), team.person(Role.VIEWER), other.person(Role.OWNER), person())) {
			assertThat(status(send(bystander, HttpMethod.GET, current, null))).isEqualTo(404);
			assertThat(status(send(bystander, HttpMethod.GET, one, null))).isEqualTo(404);
		}
		assertThat(status(send(other.person(Role.OWNER), HttpMethod.GET, "/api/v1/companies/" + other.companyId() + "/ownership-transfers/" + id, null)))
			.as("this company's transfer id under another company").isEqualTo(404);
		assertThat(status(send(team.person(Role.OWNER), HttpMethod.GET, transfers(team.companyId()) + "/not-a-uuid", null))).isEqualTo(404);
	}

	@Test
	void anOverdueTransferReadsAsExpiredBeforeAnySweepAndNothingIsPendingForTheNextReader() throws Exception {
		Team team = team();
		makeOverdue(started(team, Role.ADMIN));

		MvcResult read = send(team.person(Role.ADMIN), HttpMethod.GET, "/api/v1/companies/" + team.companyId() + "/ownership-transfer", null);

		assertThat(status(read)).isEqualTo(200);
		assertThat(parse(read).get("status").asString()).isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE company_id = ?", String.class, team.companyId()))
			.as("reading changed nothing").isEqualTo("PENDING");
	}

	// ---- accepting and cancelling

	@Test
	void theRecipientAcceptsOwnershipChangesHandsTheSessionIsRotatedAndTheOldCookieStopsWorking() throws Exception {
		Team team = team();
		UUID id = started(team, Role.ADMIN);
		Person admin = team.person(Role.ADMIN);
		String ip = freshIp();

		MvcResult accepted = api.send(HttpMethod.POST, transfers(team.companyId()) + "/" + id + "/accept", null, admin.cookie(),
				api.sessionCsrf(admin.cookie(), ip), ip);

		assertThat(status(accepted)).isEqualTo(200);
		JsonNode body = parse(accepted);
		assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(body.get("youAreRecipient").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, team.seat(Role.ADMIN))).isEqualTo("OWNER");
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, team.seat(Role.OWNER))).isEqualTo("ADMIN");
		Cookie fresh = sessionCookie(accepted);
		assertThat(fresh.getValue()).as("a new session identifier").isNotEqualTo(admin.cookie().getValue());
		assertThat(api.get("/api/v1/auth/session", admin.cookie(), freshIp()).getResponse().getStatus()).as("the old cookie").isEqualTo(401);
		assertThat(api.get("/api/v1/auth/session", fresh, freshIp()).getResponse().getStatus()).as("the new cookie").isEqualTo(200);
		// The new Owner sees their new role on the company and can use Owner-only operations; the old Owner no longer can.
		assertThat(parse(api.get("/api/v1/companies/" + team.companyId(), fresh, freshIp())).get("role").asString()).isEqualTo("OWNER");
		assertThat(status(initiate(team.person(Role.OWNER), team.companyId(), team.seat(Role.AGENT)))).isEqualTo(403);
	}

	@Test
	void everyoneButTheRecipientIsToldTheTransferDoesNotExistWhenTryingToAcceptIt() throws Exception {
		Team team = team();
		Team other = team();
		UUID id = started(team, Role.ADMIN);
		String accept = transfers(team.companyId()) + "/" + id + "/accept";

		for (Person nope : List.of(team.person(Role.OWNER), team.person(Role.AGENT), team.person(Role.VIEWER), person())) {
			assertThat(status(send(nope, HttpMethod.POST, accept, null))).isEqualTo(404);
		}
		assertThat(status(send(other.person(Role.ADMIN), HttpMethod.POST,
				transfers(other.companyId()) + "/" + id + "/accept", null))).as("the right kind of member in another company").isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, id)).isEqualTo("PENDING");
	}

	@Test
	void aRepeatedOrExpiredOrCancelledAcceptanceIs409AndChangesNothing() throws Exception {
		Team team = team();
		UUID first = started(team, Role.ADMIN);
		Person admin = team.person(Role.ADMIN);
		String accept = transfers(team.companyId()) + "/" + first + "/accept";
		MvcResult done = send(admin, HttpMethod.POST, accept, null);
		assertThat(status(done)).isEqualTo(200);
		// Accepting rotated the session: the person carries on with the new cookie.
		Person rotated = new Person(admin.userId(), admin.email(), sessionCookie(done));

		MvcResult repeated = send(rotated, HttpMethod.POST, accept, null);
		assertThat(status(repeated)).isEqualTo(409);
		assertThat(code(repeated)).isEqualTo("CONFLICT");

		Team second = team();
		UUID cancelled = started(second, Role.AGENT);
		assertThat(status(send(second.person(Role.OWNER), HttpMethod.POST, transfers(second.companyId()) + "/" + cancelled + "/cancel", null)))
			.isEqualTo(200);
		assertThat(status(send(second.person(Role.AGENT), HttpMethod.POST, transfers(second.companyId()) + "/" + cancelled + "/accept", null)))
			.as("cancelled").isEqualTo(409);

		Team third = team();
		UUID overdue = started(third, Role.VIEWER);
		makeOverdue(overdue);
		assertThat(status(send(third.person(Role.VIEWER), HttpMethod.POST, transfers(third.companyId()) + "/" + overdue + "/accept", null)))
			.as("overdue").isEqualTo(409);
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, third.seat(Role.OWNER))).isEqualTo("OWNER");
	}

	@Test
	void onlyTheInitiatingOwnerCancelsAndTheRecipientAndOthersAreRefused() throws Exception {
		Team team = team();
		Team other = team();
		UUID id = started(team, Role.ADMIN);
		String cancel = transfers(team.companyId()) + "/" + id + "/cancel";

		assertThat(status(send(team.person(Role.ADMIN), HttpMethod.POST, cancel, null))).as("the recipient is not an Owner").isEqualTo(403);
		assertThat(status(send(team.person(Role.AGENT), HttpMethod.POST, cancel, null))).isEqualTo(403);
		assertThat(status(send(person(), HttpMethod.POST, cancel, null))).as("not a member").isEqualTo(404);
		assertThat(status(send(other.person(Role.OWNER), HttpMethod.POST, transfers(other.companyId()) + "/" + id + "/cancel", null)))
			.as("another company's Owner").isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT status FROM ownership_transfer WHERE id = ?", String.class, id)).isEqualTo("PENDING");

		clock.advance(Duration.ofMinutes(11));
		MvcResult cancelled = send(team.person(Role.OWNER), HttpMethod.POST, cancel, null);
		assertThat(status(cancelled)).as("no recent login needed to cancel").isEqualTo(200);
		assertThat(parse(cancelled).get("status").asString()).isEqualTo("CANCELLED");
	}

	@Test
	void aDeniedOwnershipRequestIsAuditedForTheCaller() throws Exception {
		Team team = team();

		initiate(team.person(Role.AGENT), team.companyId(), team.seat(Role.ADMIN));

		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_id = ? AND outcome = 'denied' "
				+ "AND reason_code = 'transfer_ownership'", team.companyId(), team.person(Role.AGENT).userId())).isEqualTo(1);
	}

}
