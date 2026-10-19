package com.example.saas.api.auth;

import java.time.Duration;
import java.util.UUID;

import com.example.saas.api.MutableClock;
import com.example.saas.api.MutableClockConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-14 (mechanism), 16, 22 and the step-up flow, with a controllable clock. */
@Import({ MutableClockConfiguration.class, com.example.saas.api.security.TestEndpointsConfiguration.class })
class RecentAuthenticationTests extends AuthIntegrationTest {

	private static final String SENSITIVE = "/api/v1/test/sensitive";

	private static final String STEP_UP = "/api/v1/auth/step-up";

	@Autowired
	MutableClock clock;

	/** The clock is shared by every test in this context, so each test starts from the present. */
	@org.junit.jupiter.api.BeforeEach
	void resetClock() {
		clock.set(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
	}

	private Cookie login(String email) throws Exception {
		return sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
	}

	private int status(String path, Cookie cookie) throws Exception {
		return api.get(path, cookie, freshIp()).getResponse().getStatus();
	}

	@Test
	void aSensitiveOperationNeedsALoginWithinTheLastTenMinutes() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = login(email);
		assertThat(status(SENSITIVE, null)).as("anonymous").isEqualTo(401);
		assertThat(status(SENSITIVE, cookie)).as("just logged in").isEqualTo(200);

		clock.advance(Duration.ofMinutes(9).plusSeconds(59));
		assertThat(status(SENSITIVE, cookie)).as("9m59s").isEqualTo(200);

		clock.advance(Duration.ofSeconds(1));
		assertThat(status(SENSITIVE, cookie)).as("exactly 10m00s is still within the window").isEqualTo(200);

		clock.advance(Duration.ofSeconds(1));
		MvcResult stale = api.get(SENSITIVE, cookie, freshIp());
		assertThat(stale.getResponse().getStatus()).as("10m01s").isEqualTo(403);
		assertThat(stale.getResponse().getContentType()).startsWith("application/problem+json");
		assertThat(stale.getResponse().getContentAsString()).contains("\"code\":\"REAUTHENTICATION_REQUIRED\"");
		assertThat(status("/api/v1/auth/session", cookie)).as("the session itself is still valid").isEqualTo(200);
	}

	@Test
	void activityAndRenewalNeverRefreshTheAuthenticationTime() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = login(email);

		for (int minute = 4; minute <= 12; minute += 4) {
			clock.advance(Duration.ofMinutes(4));
			assertThat(status("/api/v1/auth/session", cookie)).as("active at minute %d", minute).isEqualTo(200);
		}

		assertThat(status(SENSITIVE, cookie)).as("12 minutes after login, however busy").isEqualTo(403);
	}

	@Test
	void stepUpWithTheRightPasswordRefreshesItAndRotatesTheSession() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = login(email);
		clock.advance(Duration.ofMinutes(11));
		assertThat(status(SENSITIVE, cookie)).isEqualTo(403);
		String ip = freshIp();
		String csrf = api.sessionCsrf(cookie, ip);

		MvcResult stepUp = api.postWithSession(STEP_UP, json("password", STRONG_PASSWORD), cookie, csrf, ip);

		assertThat(stepUp.getResponse().getStatus()).isEqualTo(204);
		assertThat(stepUp.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		Cookie rotated = sessionCookie(stepUp);
		assertThat(rotated).isNotNull();
		assertThat(rotated.getValue()).isNotEqualTo(cookie.getValue());
		assertThat(status("/api/v1/auth/session", cookie)).as("the old identifier").isEqualTo(401);
		assertThat(status(SENSITIVE, rotated)).as("fresh authentication").isEqualTo(200);
		assertThat(api.postWithSession(STEP_UP, json("password", STRONG_PASSWORD), rotated, csrf, ip)
			.getResponse()
			.getStatus()).as("the CSRF token of the old identifier").isEqualTo(403);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.step_up.succeeded' AND target_id = ?",
				userId)).isEqualTo(1);
	}

	@Test
	void stepUpWithTheWrongPasswordChangesNothingAndCountsAgainstTheLoginThrottle() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = login(email);
		clock.advance(Duration.ofMinutes(11));
		String ip = freshIp();
		String csrf = api.sessionCsrf(cookie, ip);

		MvcResult wrong = api.postWithSession(STEP_UP, json("password", "not my password at all"), cookie, csrf, ip);

		assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
		assertThat(wrong.getResponse().getContentAsString()).contains("\"code\":\"INVALID_CREDENTIALS\"");
		assertThat(status(SENSITIVE, cookie)).as("still stale").isEqualTo(403);
		for (int i = 0; i < 4; i++) {
			api.postWithSession(STEP_UP, json("password", "still wrong " + i), cookie, csrf, ip);
		}
		assertThat(api.postWithSession(STEP_UP, json("password", STRONG_PASSWORD), cookie, csrf, ip)
			.getResponse()
			.getStatus()).as("five failures from this address, so the right password is refused too").isEqualTo(401);
	}

	@Test
	void stepUpNeedsASessionAndACsrfToken() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = login(email);
		String ip = freshIp();

		assertThat(api.postWithSession(STEP_UP, json("password", STRONG_PASSWORD), cookie, null, ip).getResponse()
			.getStatus()).as("no CSRF token").isEqualTo(403);
		assertThat(api.postWithSession(STEP_UP, json("password", STRONG_PASSWORD), null, "x", ip).getResponse()
			.getStatus()).as("no session").isEqualTo(403);
	}

	@Test
	void aSessionEndsTwelveHoursAfterLoginWhateverTheActivity() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = login(email);

		clock.advance(Duration.ofHours(11).plusMinutes(59));
		assertThat(status("/api/v1/auth/session", cookie)).as("11h59m").isEqualTo(200);

		clock.advance(Duration.ofMinutes(2));
		assertThat(status("/api/v1/auth/session", cookie)).as("12h01m").isEqualTo(401);
	}

}
