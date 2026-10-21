package com.example.saas.api.tenant;

import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-TEN-02: a failure after the company and Owner rows are written leaves no orphan company or membership. */
class CompanyCreationFailureTests extends AuthIntegrationTest {

	@Test
	void aFailureAfterTheInsertsRollsBackTheCompanyTheMembershipAndTheFirstAuditRow() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		String ip = freshIp();
		String csrf = api.sessionCsrf(cookie, ip);
		int companies = count("SELECT count(*) FROM company");
		int created = count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.company.created'");
		// A database failure on the last write of the transaction: the company, the Owner membership and the first
		// audit row are already written when the second audit row is refused.
		jdbc.execute("""
				CREATE FUNCTION test_refuse_membership_audit() RETURNS trigger AS $$
				BEGIN
				    IF NEW.event_type = 'tenant.membership.created' AND NEW.actor_id = '%s' THEN
				        RAISE EXCEPTION 'forced failure';
				    END IF;
				    RETURN NEW;
				END;
				$$ LANGUAGE plpgsql
				""".formatted(userId));
		jdbc.execute("CREATE TRIGGER test_refuse_membership_audit BEFORE INSERT ON audit_event "
				+ "FOR EACH ROW EXECUTE FUNCTION test_refuse_membership_audit()");
		try {
			MvcResult result = api.postWithSession("/api/v1/companies", json("name", "Doomed"), cookie, csrf, ip);

			assertThat(result.getResponse().getStatus()).isEqualTo(500);
		}
		finally {
			jdbc.execute("DROP TRIGGER test_refuse_membership_audit ON audit_event");
			jdbc.execute("DROP FUNCTION test_refuse_membership_audit()");
		}

		assertThat(count("SELECT count(*) FROM company")).isEqualTo(companies);
		assertThat(count("SELECT count(*) FROM membership WHERE user_id = ?", userId)).isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.company.created'"))
			.isEqualTo(created);
	}

}
