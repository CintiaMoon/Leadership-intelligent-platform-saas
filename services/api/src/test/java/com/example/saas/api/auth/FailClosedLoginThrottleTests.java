package com.example.saas.api.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-AUTH-21 for the login throttle itself (the per-address limiter is covered in {@link FailClosedTests}): when the
 * throttle's table cannot be read or written, login and step-up refuse the request instead of skipping the check.
 * The failure is real: the table is renamed away, or a trigger makes every write fail.
 */
class FailClosedLoginThrottleTests extends AuthIntegrationTest {

	@BeforeEach
	@AfterEach
	void restoreTable() {
		jdbc.execute("DROP TRIGGER IF EXISTS trg_throttle_down ON login_throttle");
		jdbc.execute("DROP FUNCTION IF EXISTS throttle_down()");
		if (count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'login_throttle_off'") > 0) {
			jdbc.execute("ALTER TABLE login_throttle_off RENAME TO login_throttle");
		}
	}

	@Test
	void loginRefusesWhenTheThrottleCannotBeRead() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		int sessionsBefore = count("SELECT count(*) FROM spring_session");
		jdbc.execute("ALTER TABLE login_throttle RENAME TO login_throttle_off");

		MvcResult result = api.login(email, STRONG_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(sessionCookie(result)).isNull();
		assertThat(count("SELECT count(*) FROM spring_session")).isEqualTo(sessionsBefore);
	}

	@Test
	void aFailedLoginThatCannotBeCountedIsRefusedNotIgnored() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		jdbc.execute("CREATE FUNCTION throttle_down() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'down'; END; $$ "
				+ "LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER trg_throttle_down BEFORE INSERT OR UPDATE ON login_throttle "
				+ "FOR EACH ROW EXECUTE FUNCTION throttle_down()");

		MvcResult result = api.login(email, "not the password at all 1!", freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
	}

	@Test
	void stepUpRefusesWhenTheThrottleCannotBeRead() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		Cookie session = sessionCookie(api.login(email, STRONG_PASSWORD, ip));
		String csrf = api.sessionCsrf(session, ip);
		jdbc.execute("ALTER TABLE login_throttle RENAME TO login_throttle_off");

		MvcResult result = api.postWithSession("/api/v1/auth/step-up", json("password", STRONG_PASSWORD), session, csrf,
				ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
	}

}
