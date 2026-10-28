package com.example.saas.api.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.RecordingEmailSender;
import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Accepting an invitation over HTTP, against PostgreSQL (SPEC-INV, Phase 4). */
class InvitationAcceptTests extends AuthIntegrationTest {

	private static final String ACCEPT = "/api/v1/invitations/accept";

	@Autowired
	private InvitationService service;

	@Autowired
	private TransactionTemplate tx;

	private record Person(UUID userId, String email, Cookie cookie) {

		AuthenticatedUser session() {
			Instant now = Instant.now();
			return new AuthenticatedUser(userId, now, now.plus(Duration.ofHours(12)));
		}

	}

	private record Company(UUID id, Person owner, Person admin) {
	}

	private record Issued(UUID invitationId, String email, String token) {
	}

	private Person person(String email) throws Exception {
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new Person(userId, email, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	private Person person() throws Exception {
		return person(AuthApi.uniqueEmail());
	}

	private void seat(UUID companyId, UUID userId, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, userId, role, status);
	}

	private Company company() throws Exception {
		Person owner = person();
		Person admin = person();
		UUID id = tx.execute(s -> {
			UUID companyId = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Accept Co', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			seat(companyId, owner.userId(), "OWNER", "ACTIVE");
			seat(companyId, admin.userId(), "ADMIN", "ACTIVE");
			return companyId;
		});
		return new Company(id, owner, admin);
	}

	/** Creates an invitation as {@code inviter}, delivers the email, and returns the token a person would click. */
	private Issued issue(Company company, Person inviter, String email, Role role) {
		UUID id = service.create(inviter.session(), company.id(), email, role).id();
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		// The invitee registers next, and the registration helper reads the first email to this address as its
		// verification link: forget the invitation email so it reads the right one.
		mail.clear();
		return new Issued(id, email, token);
	}

	private MvcResult accept(Person who, String token) throws Exception {
		return accept(who, token, freshIp());
	}

	private MvcResult accept(Person who, String token, String ip) throws Exception {
		return api.send(HttpMethod.POST, ACCEPT, json("token", token), who.cookie(), api.sessionCsrf(who.cookie(), ip), ip);
	}

	private String status(UUID invitationId) {
		return jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitationId);
	}

	private int liveMemberships(UUID companyId, UUID userId) {
		return count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", companyId, userId);
	}

	private static String guest() {
		return "guest." + UUID.randomUUID() + "@example.com";
	}

	// ---- the happy path and the audit trail

	@Test
	void theInvitedUserJoinsWithTheInvitedRoleAndTheTokenIsSpent() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.VIEWER);
		Person invitee = person(email);

		MvcResult result = accept(invitee, issued.token());

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = parse(result);
		assertThat(body.get("id").asString()).isEqualTo(company.id().toString());
		assertThat(body.get("name").asString()).isEqualTo("Accept Co");
		assertThat(body.get("role").asString()).isEqualTo("VIEWER");
		assertThat(result.getResponse().getContentAsString()).doesNotContain(issued.token());
		assertThat(jdbc.queryForObject("SELECT role || ':' || status FROM membership WHERE company_id = ? AND user_id = ?",
				String.class, company.id(), invitee.userId())).isEqualTo("VIEWER:ACTIVE");
		assertThat(status(issued.invitationId())).isEqualTo("ACCEPTED");
		assertThat(jdbc.queryForObject("SELECT token_hash IS NULL AND accepted_by = ? FROM invitation WHERE id = ?",
				Boolean.class, invitee.userId(), issued.invitationId())).isTrue();
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", TokenCodec.hash(issued.token())))
			.as("the hash is gone with the token").isZero();
		assertThat(api.get("/api/v1/companies/" + company.id(), invitee.cookie(), freshIp()).getResponse().getStatus())
			.as("the new member can read the company").isEqualTo(200);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_id = ? AND outcome = 'success' "
				+ "AND ((event_type = 'invitation.accepted' AND before_value = 'PENDING' AND after_value = 'ACCEPTED') "
				+ "OR (event_type = 'membership.created' AND after_value = 'VIEWER'))", company.id(), invitee.userId()))
			.isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT string_agg(row_to_json(a)::text, '|') FROM audit_event a WHERE company_id = ? "
				+ "AND actor_id = ?", String.class, company.id(), invitee.userId()))
			.as("no token, no address in the audit rows").doesNotContain(issued.token(), email);
	}

	@Test
	void anAdminRoleIsGrantedWhenAnOwnerInvitedAnAdmin() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.ADMIN);
		Person invitee = person(email);

		assertThat(accept(invitee, issued.token()).getResponse().getStatus()).isEqualTo(200);

		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE company_id = ? AND user_id = ?", String.class,
				company.id(), invitee.userId())).isEqualTo("ADMIN");
	}

	// ---- the subscription state (AC-PLAN-04, AC-PLAN-19, AC-PLAN-20; plan Q-10)

	/** Moves the trial back in time by whole hours, so the company is read-only (337) or locked (673 and more). */
	private void agedTrial(UUID companyId, int hours) {
		jdbc.update("UPDATE company_subscription SET trial_started_at = trial_started_at - make_interval(hours => ?), "
				+ "trial_ends_at = trial_ends_at - make_interval(hours => ?), "
				+ "read_only_ends_at = read_only_ends_at - make_interval(hours => ?) WHERE company_id = ?", hours, hours,
				hours, companyId);
	}

	@Test
	void anInvitationCannotBeAcceptedWhileTheCompanyIsReadOnlyOrLockedAndItStaysPending() throws Exception {
		for (int hours : new int[] { 337, 700 }) {
			Company company = company();
			String email = guest();
			Issued issued = issue(company, company.owner(), email, Role.VIEWER);
			Person invitee = person(email);
			agedTrial(company.id(), hours);

			MvcResult result = accept(invitee, issued.token());

			assertThat(result.getResponse().getStatus()).as("after %d hours", hours).isEqualTo(409);
			assertThat(parse(result).get("code").asString()).as("the state is not told to the invitee").isEqualTo("CONFLICT");
			assertThat(status(issued.invitationId())).isEqualTo("PENDING");
			assertThat(liveMemberships(company.id(), invitee.userId())).isZero();
		}
	}

	@Test
	void anInvitationCanBeAcceptedWhileTheTrialStillRuns() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.VIEWER);
		Person invitee = person(email);
		// The trial ends in one hour, so it is still running.
		jdbc.update("UPDATE company_subscription SET trial_started_at = now() - interval '335 hours', "
				+ "trial_ends_at = now() - interval '335 hours' + interval '336 hours', "
				+ "read_only_ends_at = now() - interval '335 hours' + interval '672 hours' WHERE company_id = ?",
				company.id());

		assertThat(accept(invitee, issued.token()).getResponse().getStatus()).isEqualTo(200);
	}

	// ---- authentication (AC-INV-08) and CSRF

	@Test
	void anUnauthenticatedCallerOrOneWhoseEmailIsNoLongerVerifiedCannotAcceptAndTheInvitationIsNotConsumed()
			throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.AGENT);
		Person invitee = person(email);
		String csrf = api.sessionCsrf(invitee.cookie(), freshIp());

		MvcResult anonymous = api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()), null, null, freshIp());
		jdbc.update("UPDATE app_user SET email_verified_at = NULL WHERE id = ?", invitee.userId());
		MvcResult unverified = api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()), invitee.cookie(), csrf,
				freshIp());

		assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);
		assertThat(unverified.getResponse().getStatus()).isIn(401, 403);
		assertThat(status(issued.invitationId())).isEqualTo("PENDING");
		assertThat(count("SELECT count(*) FROM invitation WHERE token_hash = ?", TokenCodec.hash(issued.token())))
			.isEqualTo(1);
		assertThat(liveMemberships(company.id(), invitee.userId())).isZero();
	}

	@Test
	void theSessionBoundCsrfTokenIsRequired() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.AGENT);
		Person invitee = person(email);

		MvcResult noCsrf = api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()), invitee.cookie(), null,
				freshIp());

		assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
		assertThat(status(issued.invitationId())).isEqualTo("PENDING");
	}

	@Test
	void followingALinkNeverVerifiesAnAddressSoANewUserMustVerifyBeforeAccepting() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.AGENT);

		api.signup(email, STRONG_PASSWORD, "New Person", freshIp());
		drainOutbox();

		assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM app_user WHERE email_normalized = ?",
				Boolean.class, email)).as("the invitation did not verify the address").isTrue();
		assertThat(api.login(email, STRONG_PASSWORD, freshIp()).getResponse().getStatus())
			.as("an unverified account has no session to accept with").isEqualTo(401);
		api.verify(RecordingEmailSender.tokenIn(mail.sentTo(email).get(mail.sentTo(email).size() - 1)), freshIp());
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		UUID userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", UUID.class, email);
		assertThat(accept(new Person(userId, email, cookie), issued.token()).getResponse().getStatus()).isEqualTo(200);
	}

	// ---- one answer for every reason the invitation cannot be used (AC-INV-03, 09, 14, 15, 22)

	@Test
	void everyReasonTheInvitationIsUnusableGivesTheSameAnswer() throws Exception {
		Company company = company();
		Person stranger = person();
		List<MvcResult> answers = new ArrayList<>();

		// unknown token
		answers.add(accept(stranger, TokenCodec.newToken()));
		// right token, wrong signed-in address
		Issued mismatch = issue(company, company.owner(), guest(), Role.AGENT);
		answers.add(accept(stranger, mismatch.token()));
		// expired
		String lateEmail = guest();
		Issued late = issue(company, company.owner(), lateEmail, Role.AGENT);
		Person lateInvitee = person(lateEmail);
		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() - interval '1 second' WHERE id = ?", late.invitationId());
		answers.add(accept(lateInvitee, late.token()));
		// revoked
		String revokedEmail = guest();
		Issued revoked = issue(company, company.owner(), revokedEmail, Role.AGENT);
		Person revokedInvitee = person(revokedEmail);
		service.revoke(company.owner().session(), company.id(), revoked.invitationId());
		answers.add(accept(revokedInvitee, revoked.token()));
		// replaced by a resend (the new token is not delivered yet)
		String resentEmail = guest();
		Issued resent = issue(company, company.owner(), resentEmail, Role.AGENT);
		Person resentInvitee = person(resentEmail);
		service.resend(company.owner().session(), company.id(), resent.invitationId());
		answers.add(accept(resentInvitee, resent.token()));
		// already used
		String usedEmail = guest();
		Issued used = issue(company, company.owner(), usedEmail, Role.AGENT);
		Person usedInvitee = person(usedEmail);
		assertThat(accept(usedInvitee, used.token()).getResponse().getStatus()).isEqualTo(200);
		answers.add(accept(usedInvitee, used.token()));
		// company pending deletion
		Company closing = company();
		String closingEmail = guest();
		Issued inClosing = issue(closing, closing.owner(), closingEmail, Role.AGENT);
		Person closingInvitee = person(closingEmail);
		jdbc.update("UPDATE company SET status = 'PENDING_DELETION' WHERE id = ?", closing.id());
		answers.add(accept(closingInvitee, inClosing.token()));

		assertThat(answers).hasSize(7);
		String expectedBody = answers.get(0).getResponse().getContentAsString()
			.replaceAll("\"instance\":\"[^\"]*\"", "");
		for (int i = 0; i < answers.size(); i++) {
			MvcResult answer = answers.get(i);
			assertThat(answer.getResponse().getStatus()).as("case %d", i).isEqualTo(422);
			assertThat(parse(answer).get("code").asString()).as("case %d", i).isEqualTo("TOKEN_INVALID");
			assertThat(answer.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\"", ""))
				.as("case %d has the same body", i).isEqualTo(expectedBody);
		}
		assertThat(liveMemberships(company.id(), stranger.userId())).isZero();
		assertThat(liveMemberships(closing.id(), closingInvitee.userId())).as("no membership in a company pending deletion")
			.isZero();
		assertThat(status(mismatch.invitationId())).as("a mismatch does not consume it").isEqualTo("PENDING");
	}

	@Test
	void aResendKillsTheOldTokenAndOnlyTheNewOneWorks() throws Exception {
		Company company = company();
		String email = guest();
		Issued first = issue(company, company.owner(), email, Role.AGENT);
		Person invitee = person(email);
		service.resend(company.owner().session(), company.id(), first.invitationId());
		mail.clear();
		drainOutbox();
		String second = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));

		assertThat(accept(invitee, first.token()).getResponse().getStatus()).isEqualTo(422);
		assertThat(accept(invitee, second).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void anInvitationIsUsableUntilItsExpiryAndNotAtOrAfterIt() throws Exception {
		Company company = company();
		String earlyEmail = guest();
		String edgeEmail = guest();
		Issued early = issue(company, company.owner(), earlyEmail, Role.AGENT);
		Issued edge = issue(company, company.owner(), edgeEmail, Role.AGENT);
		Person earlyInvitee = person(earlyEmail);
		Person edgeInvitee = person(edgeEmail);
		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() + interval '30 seconds' WHERE id = ?", early.invitationId());
		jdbc.update("UPDATE invitation SET created_at = created_at - interval '8 days', "
				+ "expires_at = now() - interval '1 millisecond' WHERE id = ?", edge.invitationId());

		assertThat(accept(earlyInvitee, early.token()).getResponse().getStatus()).as("just before the end").isEqualTo(200);
		assertThat(accept(edgeInvitee, edge.token()).getResponse().getStatus()).as("just after the end").isEqualTo(422);
	}

	// ---- existing members and the member limit hook (AC-INV-10, 29)

	@Test
	void anInviteeWhoIsAlreadyAnActiveOrSuspendedMemberGetsAConflictAndNothingChanges() throws Exception {
		Company company = company();
		String activeEmail = guest();
		String suspendedEmail = guest();
		Issued forActive = issue(company, company.owner(), activeEmail, Role.AGENT);
		Issued forSuspended = issue(company, company.owner(), suspendedEmail, Role.AGENT);
		Person active = person(activeEmail);
		Person suspended = person(suspendedEmail);
		// They joined by another route after being invited.
		tx.executeWithoutResult(s -> {
			seat(company.id(), active.userId(), "VIEWER", "ACTIVE");
			seat(company.id(), suspended.userId(), "VIEWER", "SUSPENDED");
		});

		MvcResult activeResult = accept(active, forActive.token());
		MvcResult suspendedResult = accept(suspended, forSuspended.token());

		for (MvcResult conflict : List.of(activeResult, suspendedResult)) {
			assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
			assertThat(parse(conflict).get("code").asString()).isEqualTo("CONFLICT");
		}
		assertThat(status(forActive.invitationId())).as("left pending").isEqualTo("PENDING");
		assertThat(liveMemberships(company.id(), active.userId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT role FROM membership WHERE company_id = ? AND user_id = ?", String.class,
				company.id(), active.userId())).as("no role change").isEqualTo("VIEWER");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE company_id = ? AND user_id = ?", String.class,
				company.id(), suspended.userId())).as("a suspension is not lifted by an invitation").isEqualTo("SUSPENDED");
	}

	@Test
	void aRemovedMemberCanBeInvitedAndJoinsAgainWithANewMembershipRow() throws Exception {
		Company company = company();
		String email = guest();
		Person returning = person(email);
		tx.executeWithoutResult(s -> seat(company.id(), returning.userId(), "AGENT", "REMOVED"));
		Issued issued = issue(company, company.owner(), email, Role.VIEWER);

		assertThat(accept(returning, issued.token()).getResponse().getStatus()).isEqualTo(200);

		assertThat(liveMemberships(company.id(), returning.userId())).as("history kept, one new row").isEqualTo(2);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ? AND status = 'ACTIVE' "
				+ "AND role = 'VIEWER'", company.id(), returning.userId())).isEqualTo(1);
	}

	// ---- the inviter (AC-INV-24, D-62)

	@Test
	void anInviterWhoIsSuspendedOrDemotedOutsideTheServiceCanNoLongerHaveTheirInvitationsAccepted() throws Exception {
		Company company = company();
		String suspendedEmail = guest();
		String demotedEmail = guest();
		String ownerEmail = guest();
		Issued bySuspended = issue(company, company.admin(), suspendedEmail, Role.AGENT);
		Issued byOwner = issue(company, company.owner(), ownerEmail, Role.AGENT);
		Person suspendedInvitee = person(suspendedEmail);
		Person ownerInvitee = person(ownerEmail);
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE company_id = ? AND user_id = ?", company.id(),
				company.admin().userId());

		assertThat(accept(suspendedInvitee, bySuspended.token()).getResponse().getStatus())
			.as("the inviter is suspended (changed directly in the database, as the operator procedure does)")
			.isEqualTo(422);
		assertThat(accept(ownerInvitee, byOwner.token()).getResponse().getStatus()).as("an Owner's invitation is unaffected")
			.isEqualTo(200);

		Company other = company();
		String demotedAdminEmail = demotedEmail;
		Issued byAdmin = issue(other, other.admin(), demotedAdminEmail, Role.AGENT);
		Person demotedInvitee = person(demotedAdminEmail);
		jdbc.update("UPDATE membership SET role = 'AGENT' WHERE company_id = ? AND user_id = ?", other.id(),
				other.admin().userId());
		assertThat(accept(demotedInvitee, byAdmin.token()).getResponse().getStatus()).as("the inviter can no longer invite")
			.isEqualTo(422);
	}

	@Test
	void changingAnInvitersMembershipThroughTheServiceRevokesWhatTheyCanNoLongerGrantInTheSameTransaction()
			throws Exception {
		Company company = company();
		String[] emails = { guest(), guest(), guest(), guest(), guest(), guest() };
		Issued suspendedA = issue(company, company.admin(), emails[0], Role.AGENT);
		Issued suspendedB = issue(company, company.admin(), emails[1], Role.VIEWER);
		Issued ownerOne = issue(company, company.owner(), emails[2], Role.ADMIN);
		Person invitee = person(emails[0]);
		String base = "/api/v1/companies/" + company.id() + "/members/";
		UUID adminSeat = jdbc.queryForObject("SELECT id FROM membership WHERE company_id = ? AND user_id = ?", UUID.class,
				company.id(), company.admin().userId());

		String ip = freshIp();
		MvcResult suspend = api.send(HttpMethod.POST, base + adminSeat + "/suspend", null, company.owner().cookie(),
				api.sessionCsrf(company.owner().cookie(), ip), ip);

		assertThat(suspend.getResponse().getStatus()).isEqualTo(200);
		assertThat(status(suspendedA.invitationId())).isEqualTo("REVOKED");
		assertThat(status(suspendedB.invitationId())).isEqualTo("REVOKED");
		assertThat(status(ownerOne.invitationId())).as("the Owner's invitations are untouched").isEqualTo("PENDING");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'invitation.revoked' AND company_id = ? "
				+ "AND actor_id = ? AND reason_code = 'inviter_changed' AND before_value = 'PENDING' "
				+ "AND after_value = 'REVOKED'", company.id(), company.owner().userId())).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT revoked_by FROM invitation WHERE id = ?", UUID.class,
				suspendedA.invitationId())).isEqualTo(company.owner().userId());
		assertThat(accept(invitee, suspendedA.token()).getResponse().getStatus()).as("can no longer be accepted")
			.isEqualTo(422);
		api.send(HttpMethod.POST, base + adminSeat + "/reactivate", null, company.owner().cookie(),
				api.sessionCsrf(company.owner().cookie(), ip), ip);
		assertThat(status(suspendedA.invitationId())).as("reactivating does not bring them back").isEqualTo("REVOKED");
	}

	@Test
	void removalLeavingAndDemotionOfTheInviterAlsoRevokeTheirInvitations() throws Exception {
		for (String how : new String[] { "remove", "leave", "demote" }) {
			Company company = company();
			Issued pending = issue(company, company.admin(), guest(), Role.AGENT);
			UUID adminSeat = jdbc.queryForObject("SELECT id FROM membership WHERE company_id = ? AND user_id = ?",
					UUID.class, company.id(), company.admin().userId());
			String base = "/api/v1/companies/" + company.id();
			String ip = freshIp();
			MvcResult result = switch (how) {
				case "remove" -> api.send(HttpMethod.DELETE, base + "/members/" + adminSeat, null, company.owner().cookie(),
						api.sessionCsrf(company.owner().cookie(), ip), ip);
				case "leave" -> api.send(HttpMethod.POST, base + "/leave", null, company.admin().cookie(),
						api.sessionCsrf(company.admin().cookie(), ip), ip);
				default -> api.send(HttpMethod.PATCH, base + "/members/" + adminSeat, json("role", "AGENT"),
						company.owner().cookie(), api.sessionCsrf(company.owner().cookie(), ip), ip);
			};

			assertThat(result.getResponse().getStatus()).as(how).isIn(200, 204);
			assertThat(status(pending.invitationId())).as("after %s", how).isEqualTo("REVOKED");
		}
	}

	// ---- the token only travels in the body (AC-INV-18) and abuse control (AC-INV-11)

	@Test
	void aTokenInThePathOrTheQueryStringIsNotAccepted() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.AGENT);
		Person invitee = person(email);
		String ip = freshIp();
		String csrf = api.sessionCsrf(invitee.cookie(), ip);

		MvcResult query = api.send(HttpMethod.POST, ACCEPT + "?token=" + issued.token(), "{}", invitee.cookie(), csrf, ip);
		MvcResult path = api.send(HttpMethod.POST, ACCEPT + "/" + issued.token(), null, invitee.cookie(), csrf, ip);
		MvcResult alsoQuery = api.send(HttpMethod.POST, ACCEPT + "?token=" + issued.token(),
				json("token", TokenCodec.newToken()), invitee.cookie(), csrf, ip);

		assertThat(query.getResponse().getStatus()).as("a body without the token field").isEqualTo(400);
		assertThat(path.getResponse().getStatus()).as("there is no such route").isIn(403, 404);
		assertThat(alsoQuery.getResponse().getStatus()).as("the query value is ignored; the body token is wrong")
			.isEqualTo(422);
		assertThat(status(issued.invitationId())).isEqualTo("PENDING");
		assertThat(liveMemberships(company.id(), invitee.userId())).isZero();
	}

	@Test
	void malformedBodiesAreBadRequestsAndAnOversizedTokenIsJustInvalid() throws Exception {
		Person someone = person();
		String ip = freshIp();
		String csrf = api.sessionCsrf(someone.cookie(), ip);

		for (String body : new String[] { "{}", "{\"token\":\"\"}", "{\"token\":\"   \"}", "{\"token\":null}" }) {
			assertThat(api.send(HttpMethod.POST, ACCEPT, body, someone.cookie(), csrf, ip).getResponse().getStatus())
				.as(body).isEqualTo(400);
		}
		assertThat(api.send(HttpMethod.POST, ACCEPT, "{\"token\":\"x\",\"companyId\":\"" + UUID.randomUUID() + "\"}",
				someone.cookie(), csrf, ip).getResponse().getStatus()).as("a company in the body is not accepted").isEqualTo(400);
		assertThat(api.send(HttpMethod.POST, ACCEPT, json("token", "x".repeat(5_000)), someone.cookie(), csrf, ip)
			.getResponse().getStatus()).isEqualTo(422);
	}

	@Test
	void theEleventhFailedAttemptFromOneAddressIsThrottledButSuccessesDoNotCount() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.AGENT);
		Person invitee = person(email);
		String ip = freshIp();

		for (int i = 0; i < 5; i++) {
			assertThat(accept(invitee, TokenCodec.newToken(), ip).getResponse().getStatus()).as("failure %d", i + 1)
				.isEqualTo(422);
		}
		assertThat(accept(invitee, issued.token(), ip).getResponse().getStatus()).as("a success from the same address")
			.isEqualTo(200);
		for (int i = 5; i < 10; i++) {
			assertThat(accept(invitee, TokenCodec.newToken(), ip).getResponse().getStatus()).as("failure %d", i + 1)
				.isEqualTo(422);
		}

		MvcResult eleventh = accept(invitee, TokenCodec.newToken(), ip);

		assertThat(eleventh.getResponse().getStatus()).isEqualTo(429);
		assertThat(eleventh.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(parse(eleventh).get("code").asString()).isEqualTo("RATE_LIMITED");
		assertThat(accept(invitee, TokenCodec.newToken(), freshIp()).getResponse().getStatus())
			.as("another address is unaffected").isEqualTo(422);
	}

	// ---- concurrency (AC-INV-04)

	@Test
	void manyAcceptancesOfOneTokenAtOnceCreateExactlyOneMembership() throws Exception {
		Company company = company();
		String email = guest();
		Issued issued = issue(company, company.owner(), email, Role.AGENT);
		Person invitee = person(email);
		String csrf = api.sessionCsrf(invitee.cookie(), freshIp());
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()), invitee.cookie(), csrf,
					freshIp()).getResponse().getStatus());
		}

		List<Integer> statuses = Parallel.runAll(tasks);

		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(9);
		assertThat(liveMemberships(company.id(), invitee.userId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.created' AND company_id = ? "
				+ "AND actor_id = ?", company.id(), invitee.userId())).isEqualTo(1);
	}

	@Test
	void anAcceptanceRacingARevokeOrAResendEndsInOneOfTwoSerialOrders() throws Exception {
		Company company = company();
		for (int round = 0; round < 6; round++) {
			boolean revoke = round % 2 == 0;
			String email = guest();
			Issued issued = issue(company, company.owner(), email, Role.AGENT);
			Person invitee = person(email);
			String csrf = api.sessionCsrf(invitee.cookie(), freshIp());
			Callable<Integer> acceptTask = () -> api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()),
					invitee.cookie(), csrf, freshIp()).getResponse().getStatus();
			Callable<Integer> changeTask = () -> {
				try {
					if (revoke) {
						service.revoke(company.owner().session(), company.id(), issued.invitationId());
					}
					else {
						service.resend(company.owner().session(), company.id(), issued.invitationId());
					}
					return 200;
				}
				catch (com.example.saas.api.invitation.application.InvitationConflictException e) {
					return 409;
				}
			};

			List<Integer> results = Parallel.runAll(List.of(acceptTask, changeTask));

			boolean acceptedFirst = results.get(0) == 200 && results.get(1) == 409;
			boolean changedFirst = results.get(0) == 422 && results.get(1) == 200;
			assertThat(acceptedFirst || changedFirst).as("round %d (%s): accept=%d change=%d", round,
					revoke ? "revoke" : "resend", results.get(0), results.get(1)).isTrue();
			assertThat(liveMemberships(company.id(), invitee.userId())).isEqualTo(acceptedFirst ? 1 : 0);
			assertThat(status(issued.invitationId())).isEqualTo(acceptedFirst ? "ACCEPTED" : revoke ? "REVOKED" : "PENDING");
		}
	}

	@Test
	void anAcceptanceRacingTheSuspensionOfTheInviterEndsInOneOfTwoSerialOrders() throws Exception {
		for (int round = 0; round < 5; round++) {
			Company company = company();
			String email = guest();
			Issued issued = issue(company, company.admin(), email, Role.AGENT);
			Person invitee = person(email);
			String csrf = api.sessionCsrf(invitee.cookie(), freshIp());
			UUID adminSeat = jdbc.queryForObject("SELECT id FROM membership WHERE company_id = ? AND user_id = ?",
					UUID.class, company.id(), company.admin().userId());
			String ownerCsrf = api.sessionCsrf(company.owner().cookie(), freshIp());
			Callable<Integer> acceptTask = () -> api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()),
					invitee.cookie(), csrf, freshIp()).getResponse().getStatus();
			Callable<Integer> suspendTask = () -> api.send(HttpMethod.POST,
					"/api/v1/companies/" + company.id() + "/members/" + adminSeat + "/suspend", null,
					company.owner().cookie(), ownerCsrf, freshIp()).getResponse().getStatus();

			List<Integer> results = Parallel.runAll(List.of(acceptTask, suspendTask));

			assertThat(results.get(1)).as("the suspension always succeeds").isEqualTo(200);
			assertThat(results.get(0)).as("round %d", round).isIn(200, 422);
			assertThat(liveMemberships(company.id(), invitee.userId())).isEqualTo(results.get(0) == 200 ? 1 : 0);
			assertThat(status(issued.invitationId())).isEqualTo(results.get(0) == 200 ? "ACCEPTED" : "REVOKED");
		}
	}

	@Test
	void anInvitersDisabledAccountMakesTheirPendingInvitationsUnusableEvenThoughTheMembershipRemains() throws Exception {
		Company company = company();
		String byAdminEmail = guest();
		String byOwnerEmail = guest();
		Issued byAdmin = issue(company, company.admin(), byAdminEmail, Role.AGENT);
		Issued byOwner = issue(company, company.owner(), byOwnerEmail, Role.AGENT);
		Person adminInvitee = person(byAdminEmail);
		Person ownerInvitee = person(byOwnerEmail);
		// The operator disable procedure changes the account and leaves non-owned memberships ACTIVE (D-141).
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", company.admin().userId());

		assertThat(accept(adminInvitee, byAdmin.token()).getResponse().getStatus()).isEqualTo(422);
		assertThat(status(byAdmin.invitationId())).isEqualTo("PENDING");
		assertThat(liveMemberships(company.id(), adminInvitee.userId())).isZero();
		assertThat(accept(ownerInvitee, byOwner.token()).getResponse().getStatus()).as("an active inviter's is unaffected")
			.isEqualTo(200);
	}

	@Test
	void anAcceptanceRacingTheRemovalOfTheInviteesExistingMembershipEndsInOneOfTwoSerialOrders() throws Exception {
		for (int round = 0; round < 5; round++) {
			Company company = company();
			String email = guest();
			Issued issued = issue(company, company.owner(), email, Role.AGENT);
			Person invitee = person(email);
			// The address became a member by another route after the invitation was sent.
			seat(company.id(), invitee.userId(), "VIEWER", "ACTIVE");
			UUID seatId = jdbc.queryForObject("SELECT id FROM membership WHERE company_id = ? AND user_id = ?", UUID.class,
					company.id(), invitee.userId());
			String csrf = api.sessionCsrf(invitee.cookie(), freshIp());
			String ownerCsrf = api.sessionCsrf(company.owner().cookie(), freshIp());
			Callable<Integer> acceptTask = () -> api.send(HttpMethod.POST, ACCEPT, json("token", issued.token()),
					invitee.cookie(), csrf, freshIp()).getResponse().getStatus();
			Callable<Integer> removeTask = () -> api.send(HttpMethod.DELETE,
					"/api/v1/companies/" + company.id() + "/members/" + seatId, null, company.owner().cookie(), ownerCsrf,
					freshIp()).getResponse().getStatus();

			List<Integer> results = Parallel.runAll(List.of(acceptTask, removeTask));

			assertThat(results.get(1)).as("the removal always succeeds (round %d)", round).isIn(200, 204);
			assertThat(results.get(0)).isIn(200, 409);
			assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ? "
					+ "AND status IN ('ACTIVE', 'SUSPENDED')", company.id(), invitee.userId()))
				.as("never two live memberships; one only if the acceptance came after the removal")
				.isEqualTo(results.get(0) == 200 ? 1 : 0);
			assertThat(status(issued.invitationId())).isEqualTo(results.get(0) == 200 ? "ACCEPTED" : "PENDING");
		}
	}

}
