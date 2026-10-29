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
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.ActiveMemberLimit;
import com.example.saas.api.tenant.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-INV-12 and AC-INV-26: accepting is rejected when the company has no room, including under concurrent acceptances,
 * and creating an invitation is not. The real plan-based limit arrives in slice 006, which closes the criterion; here a
 * test limit of five active members per company stands in for it, counted from committed rows under the company lock.
 */
@Import(InvitationAcceptLimitTests.FiveActiveMembers.class)
class InvitationAcceptLimitTests extends AuthIntegrationTest {

	static final int CAPACITY = 5;

	@TestConfiguration(proxyBeanMethods = false)
	static class FiveActiveMembers {

		@Bean
		@Primary
		ActiveMemberLimit fiveActiveMembers(JdbcTemplate jdbc) {
			return companyId -> jdbc.queryForObject(
					"SELECT count(*) FROM membership WHERE company_id = ? AND status = 'ACTIVE'", Integer.class, companyId)
					< CAPACITY;
		}

	}

	@Autowired
	private InvitationService service;

	@Autowired
	private TransactionTemplate tx;

	private record Invitee(UUID userId, Cookie cookie, String token) {
	}

	private record Company(UUID id, AuthenticatedUser owner) {
	}

	/** A company with two active members (the Owner and an Admin), so three places are left. */
	private Company company() {
		return tx.execute(s -> {
			UUID owner = user();
			UUID admin = user();
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Capped', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) VALUES "
					+ "(?, ?, 'OWNER', 'ACTIVE', now(), now()), (?, ?, 'ADMIN', 'ACTIVE', now(), now())", id, owner, id, admin);
			Instant now = Instant.now();
			return new Company(id, new AuthenticatedUser(owner, now, now.plus(Duration.ofHours(12))));
		});
	}

	private UUID user() {
		String email = "member." + UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "created_at, updated_at) VALUES (?, ?, 'M', 'hash', now(), now()) RETURNING id", UUID.class, email, email);
	}

	private Invitee invitee(Company company) throws Exception {
		String email = AuthApi.uniqueEmail();
		service.create(company.owner(), company.id(), email, Role.VIEWER);
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		mail.clear();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new Invitee(userId, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())), token);
	}

	private int accept(Invitee invitee) throws Exception {
		return acceptResponse(invitee).getStatus();
	}

	private MockHttpServletResponse acceptResponse(Invitee invitee) throws Exception {
		String ip = freshIp();
		return api.send(HttpMethod.POST, "/api/v1/invitations/accept", json("token", invitee.token()), invitee.cookie(),
				api.sessionCsrf(invitee.cookie(), ip), ip).getResponse();
	}

	@Test
	void aFullCompanyRejectsTheAcceptanceAndKeepsTheInvitationButStillAcceptsNewInvitations() throws Exception {
		Company company = company();
		List<Invitee> fillers = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			fillers.add(invitee(company));
		}
		for (Invitee filler : fillers) {
			assertThat(accept(filler)).isEqualTo(200);
		}
		Invitee late = invitee(company);

		MockHttpServletResponse refused = acceptResponse(late);
		assertThat(refused.getStatus()).as("no room left").isEqualTo(409);
		assertThat(refused.getContentAsString()).contains("\"MEMBER_LIMIT_REACHED\"");

		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", company.id(),
				late.userId())).isZero();
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND status = 'PENDING'", company.id()))
			.as("the late invitee's invitation is still pending").isEqualTo(1);
		service.create(company.owner(), company.id(), AuthApi.uniqueEmail(), Role.AGENT);
	}

	@Test
	void eightSimultaneousAcceptancesCannotExceedTheThreePlacesLeft() throws Exception {
		Company company = company();
		List<Invitee> invitees = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			invitees.add(invitee(company));
		}
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (Invitee invitee : invitees) {
			tasks.add(() -> accept(invitee));
		}

		List<Integer> statuses = Parallel.runAll(tasks);

		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(CAPACITY - 2);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(8 - (CAPACITY - 2));
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND status = 'ACTIVE'", company.id()))
			.isEqualTo(CAPACITY);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND status = 'PENDING'", company.id()))
			.as("the ones turned away can still be accepted later").isEqualTo(8 - (CAPACITY - 2));
	}

}
