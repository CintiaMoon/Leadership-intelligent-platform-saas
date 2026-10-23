package com.example.saas.api.tenant;

import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.application.ActiveMemberLimit;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mechanism behind AC-MEM-17: reactivation asks the {@link ActiveMemberLimit} under the company lock and is refused
 * when there is no room. The real plan-based limit arrives in slice 006, which closes the criterion.
 */
@Import(ReactivationLimitHookTests.FullCompany.class)
class ReactivationLimitHookTests extends AuthIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FullCompany {

		@Bean
		@Primary
		ActiveMemberLimit fullCompany() {
			return companyId -> false;
		}

	}

	@Test
	void aSuspendedMemberStaysSuspendedWhenThereIsNoRoomForAnotherActiveMember() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID ownerId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		UUID companyId = UUID.randomUUID();
		jdbc.update("WITH c AS (INSERT INTO company (id, name, created_at, updated_at) VALUES (?, 'Full', now(), now()) "
				+ "RETURNING id) INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "SELECT id, ?, 'OWNER', 'ACTIVE', now(), now() FROM c", companyId, ownerId);
		String otherEmail = UUID.randomUUID() + "@example.com";
		UUID other = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, otherEmail, otherEmail);
		UUID membershipId = jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, "
				+ "created_at, updated_at) VALUES (?, ?, 'AGENT', 'SUSPENDED', now(), now()) RETURNING id", UUID.class,
				companyId, other);
		String ip = freshIp();

		MvcResult result = api.send(HttpMethod.POST,
				"/api/v1/companies/" + companyId + "/members/" + membershipId + "/reactivate", null, cookie,
				api.sessionCsrf(cookie, ip), ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(AuthApi.parse(result).get("code").asString()).isEqualTo("CONFLICT");
		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, membershipId))
			.isEqualTo("SUSPENDED");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'membership.reactivated' "
				+ "AND target_id = ?", membershipId)).isZero();
	}

}
