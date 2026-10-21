package com.example.saas.api.tenant;

import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.parse;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** Company creation and reading over HTTP, against PostgreSQL (SPEC-TEN, Phase 3). */
class CompanyApiTests extends AuthIntegrationTest {

	private static final String COMPANIES = "/api/v1/companies";

	@Autowired
	private TransactionTemplate tx;

	private record User(String email, UUID userId, Cookie cookie) {
	}

	private User user() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new User(email, userId, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	private MvcResult create(User user, String body, String ip) throws Exception {
		return api.postWithSession(COMPANIES, body, user.cookie(), api.sessionCsrf(user.cookie(), ip), ip);
	}

	private MvcResult create(User user, String name) throws Exception {
		return create(user, json("name", name), freshIp());
	}

	private int membershipCount(UUID userId) {
		return count("SELECT count(*) FROM membership WHERE user_id = ?", userId);
	}

	private UUID insertCompany(UUID ownerId, String status) {
		return tx.execute(s -> {
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, status, created_at, updated_at) VALUES ('Seeded', ?, now(), now()) "
							+ "RETURNING id",
					UUID.class, status);
			join(companyId, ownerId, "OWNER", "ACTIVE");
			return companyId;
		});
	}

	private void join(UUID companyId, UUID userId, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, userId, role, status);
	}

	@Test
	void creatingACompanyMakesTheCallerItsOwnerAndAuditsIt() throws Exception {
		User user = user();

		MvcResult result = create(user, "Acme Labs");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = parse(result);
		assertThat(body.get("name").asString()).isEqualTo("Acme Labs");
		assertThat(body.get("role").asString()).isEqualTo("OWNER");
		UUID companyId = UUID.fromString(body.get("id").asString());
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ? AND role = 'OWNER' "
				+ "AND status = 'ACTIVE'", companyId, user.userId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_id = ? AND outcome = 'success' "
				+ "AND event_type IN ('tenant.company.created', 'tenant.membership.created')", companyId,
				user.userId())).isEqualTo(2);
	}

	@Test
	void aRoleOrAnyOtherUnknownFieldInTheBodyIsRejectedAndNothingIsCreated() throws Exception {
		User user = user();
		String ip = freshIp();

		MvcResult withRole = create(user, "{\"name\":\"Sneaky\",\"role\":\"OWNER\"}", ip);
		MvcResult withCompany = create(user, "{\"name\":\"Sneaky\",\"companyId\":\"" + UUID.randomUUID() + "\"}", ip);
		MvcResult withUser = create(user, "{\"name\":\"Sneaky\",\"userId\":\"" + UUID.randomUUID() + "\"}", ip);

		assertThat(withRole.getResponse().getStatus()).isEqualTo(400);
		assertThat(withCompany.getResponse().getStatus()).isEqualTo(400);
		assertThat(withUser.getResponse().getStatus()).isEqualTo(400);
		assertThat(membershipCount(user.userId())).isZero();
	}

	@Test
	void invalidNamesAreRejectedWithAFieldErrorAndNothingIsCreated() throws Exception {
		User user = user();

		for (String name : new String[] { "", "   ", "Bad\nName", "Bad\u0000Name", "x".repeat(101) }) {
			MvcResult result = create(user, name);
			assertThat(result.getResponse().getStatus()).as("name %s", name.length()).isEqualTo(400);
			assertThat(parse(result).get("code").asString()).isEqualTo("VALIDATION_FAILED");
			assertThat(parse(result).get("errors").get(0).get("field").asString()).isEqualTo("name");
		}
		assertThat(create(user, "{}", freshIp()).getResponse().getStatus()).isEqualTo(400);
		assertThat(membershipCount(user.userId())).isZero();
	}

	@Test
	void theNameIsNormalizedAndNamesAreNotUnique() throws Exception {
		User one = user();
		User two = user();

		JsonNode first = parse(create(one, "  Ａcme  "));
		JsonNode second = parse(create(two, "Acme"));

		assertThat(first.get("name").asString()).isEqualTo("Acme");
		assertThat(second.get("name").asString()).isEqualTo("Acme");
		assertThat(first.get("id").asString()).isNotEqualTo(second.get("id").asString());
	}

	@Test
	void creationNeedsASessionAndTheSessionBoundCsrfToken() throws Exception {
		User user = user();
		String ip = freshIp();

		MvcResult anonymous = api.postWithSession(COMPANIES, json("name", "X"), null, null, ip);
		MvcResult noCsrf = api.postWithSession(COMPANIES, json("name", "X"), user.cookie(), null, ip);

		// Without a live session the CSRF check rejects the request before authorization, as for logout.
		assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);
		assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
		assertThat(membershipCount(user.userId())).isZero();
	}

	@Test
	void anAccountThatIsNoLongerVerifiedCannotCreateACompany() throws Exception {
		User user = user();
		String ip = freshIp();
		String csrf = api.sessionCsrf(user.cookie(), ip);
		jdbc.update("UPDATE app_user SET email_verified_at = NULL WHERE id = ?", user.userId());

		MvcResult read = api.get(COMPANIES, user.cookie(), freshIp());
		MvcResult write = api.postWithSession(COMPANIES, json("name", "Unverified Inc"), user.cookie(), csrf, ip);

		assertThat(read.getResponse().getStatus()).isEqualTo(401);
		assertThat(parse(read).get("code").asString()).isEqualTo("UNAUTHENTICATED");
		assertThat(write.getResponse().getStatus()).isIn(401, 403);
		assertThat(membershipCount(user.userId())).isZero();
	}

	@Test
	void aDisabledAccountCannotCreateACompany() throws Exception {
		User user = user();
		String ip = freshIp();
		String csrf = api.sessionCsrf(user.cookie(), ip);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", user.userId());

		MvcResult read = api.get(COMPANIES, user.cookie(), freshIp());
		MvcResult write = api.postWithSession(COMPANIES, json("name", "Disabled Inc"), user.cookie(), csrf, ip);

		assertThat(read.getResponse().getStatus()).isEqualTo(401);
		assertThat(parse(read).get("code").asString()).isEqualTo("UNAUTHENTICATED");
		assertThat(write.getResponse().getStatus()).isIn(401, 403);
		assertThat(membershipCount(user.userId())).isZero();
	}

	@Test
	void anOversizedNameIsRejectedWithoutBeingNormalized() throws Exception {
		User user = user();

		MvcResult result = create(user, "x".repeat(5_000));

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(parse(result).get("errors").get(0).get("code").asString()).isEqualTo("INVALID_NAME");
		assertThat(membershipCount(user.userId())).isZero();
	}

	@Test
	void theSixthAttemptFromOneAddressWithinAnHourIsThrottledAndInvalidAttemptsCount() throws Exception {
		User user = user();
		String ip = freshIp();
		String csrf = api.sessionCsrf(user.cookie(), ip);

		for (int attempt = 1; attempt <= 5; attempt++) {
			String body = attempt <= 2 ? json("name", "") : json("name", "Company " + attempt);
			MvcResult result = api.postWithSession(COMPANIES, body, user.cookie(), csrf, ip);
			assertThat(result.getResponse().getStatus()).as("attempt %d", attempt).isEqualTo(attempt <= 2 ? 400 : 201);
		}
		MvcResult sixth = api.postWithSession(COMPANIES, json("name", "Company 6"), user.cookie(), csrf, ip);

		assertThat(sixth.getResponse().getStatus()).isEqualTo(429);
		assertThat(sixth.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(parse(sixth).get("code").asString()).isEqualTo("RATE_LIMITED");
		assertThat(membershipCount(user.userId())).isEqualTo(3);
	}

	@Test
	void theEleventhAttemptByOneAccountWithinADayIsThrottled() throws Exception {
		User user = user();

		for (int attempt = 1; attempt <= 10; attempt++) {
			assertThat(create(user, "Company " + attempt).getResponse().getStatus()).as("attempt %d", attempt)
				.isEqualTo(201);
		}
		MvcResult eleventh = create(user, "Company 11");

		assertThat(eleventh.getResponse().getStatus()).isEqualTo(429);
		assertThat(eleventh.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(membershipCount(user.userId())).isEqualTo(10);
	}

	@Test
	void listingShowsOnlyTheCallersActiveCompaniesWithTheirRoles() throws Exception {
		User user = user();
		User other = user();
		UUID mine = UUID.fromString(parse(create(user, "Mine")).get("id").asString());
		UUID joined = insertCompany(other.userId(), "ACTIVE");
		join(joined, user.userId(), "VIEWER", "ACTIVE");
		UUID suspended = insertCompany(other.userId(), "ACTIVE");
		join(suspended, user.userId(), "AGENT", "SUSPENDED");
		UUID removed = insertCompany(other.userId(), "ACTIVE");
		join(removed, user.userId(), "AGENT", "REMOVED");
		insertCompany(other.userId(), "ACTIVE");

		JsonNode list = parse(api.get(COMPANIES, user.cookie(), freshIp()));

		assertThat(list.size()).isEqualTo(2);
		assertThat(list.findValuesAsString("id")).containsExactlyInAnyOrder(mine.toString(), joined.toString());
		for (JsonNode item : list) {
			assertThat(item.get("role").asString()).isEqualTo(item.get("id").asString().equals(mine.toString())
					? "OWNER" : "VIEWER");
		}
	}

	@Test
	void aCompanyPendingDeletionIsListedOnlyForItsOwner() throws Exception {
		User owner = user();
		User agent = user();
		UUID companyId = insertCompany(owner.userId(), "PENDING_DELETION");
		join(companyId, agent.userId(), "AGENT", "ACTIVE");

		assertThat(parse(api.get(COMPANIES, owner.cookie(), freshIp())).findValuesAsString("id"))
			.contains(companyId.toString());
		assertThat(parse(api.get(COMPANIES, agent.cookie(), freshIp())).findValuesAsString("id"))
			.doesNotContain(companyId.toString());
	}

	@Test
	void aMemberReadsTheCompanyAndAStrangerGetsNotFound() throws Exception {
		User owner = user();
		User stranger = user();
		UUID companyId = UUID.fromString(parse(create(owner, "Readable")).get("id").asString());

		MvcResult own = api.get(COMPANIES + "/" + companyId, owner.cookie(), freshIp());
		MvcResult foreign = api.get(COMPANIES + "/" + companyId, stranger.cookie(), freshIp());
		MvcResult unknown = api.get(COMPANIES + "/" + UUID.randomUUID(), stranger.cookie(), freshIp());

		assertThat(own.getResponse().getStatus()).isEqualTo(200);
		assertThat(parse(own).get("name").asString()).isEqualTo("Readable");
		assertThat(parse(own).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(parse(own).get("role").asString()).isEqualTo("OWNER");
		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
		assertThat(parse(foreign).get("detail").asString()).isEqualTo(parse(unknown).get("detail").asString());
		assertThat(foreign.getResponse().getContentAsString()).doesNotContain("Readable");
	}

	@Test
	void anAgentIsForbiddenFromAPendingDeletionCompanyAndTheDenialIsAudited() throws Exception {
		User owner = user();
		User agent = user();
		UUID companyId = insertCompany(owner.userId(), "PENDING_DELETION");
		join(companyId, agent.userId(), "AGENT", "ACTIVE");

		MvcResult result = api.get(COMPANIES + "/" + companyId, agent.cookie(), freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_id = ? "
				+ "AND event_type = 'tenant.authorization.denied' AND reason_code = 'view_company_details'", companyId,
				agent.userId())).isEqualTo(1);
	}

	@Test
	void theResponseNeverCarriesInternalFields() throws Exception {
		User user = user();

		String body = create(user, "Plain").getResponse().getContentAsString();

		assertThat(body).doesNotContain("version", "updatedAt", "userId", "companyId");
	}

}
