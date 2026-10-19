package com.example.saas.api.auth;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.example.saas.api.auth.application.SessionRevoker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-04, 06, 07, 09, 15, 16, 23, 24 for sessions on PostgreSQL. */
@org.springframework.context.annotation.Import(com.example.saas.api.security.TestEndpointsConfiguration.class)
class SessionLifecycleTests extends AuthIntegrationTest {

	private static final String LOGOUT = "/api/v1/auth/logout";

	@Autowired
	SessionRevoker revoker;

	private record LoggedIn(String email, UUID userId, Cookie cookie) {
	}

	private LoggedIn login() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new LoggedIn(email, userId, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	private int sessionStatus(Cookie cookie) throws Exception {
		return api.get("/api/v1/auth/session", cookie, freshIp()).getResponse().getStatus();
	}

	private void ageSession(UUID userId, Duration idle, Duration age) {
		jdbc.update("UPDATE spring_session SET last_access_time = last_access_time - ?, "
				+ "expiry_time = expiry_time - ?, creation_time = creation_time - ? WHERE principal_name = ?",
				idle.toMillis(), idle.toMillis(), age.toMillis(), userId.toString());
	}

	@Test
	void logoutDestroysTheSessionAndClearsTheCookie() throws Exception {
		LoggedIn user = login();
		String ip = freshIp();
		String csrf = api.sessionCsrf(user.cookie(), ip);

		MvcResult result = api.postWithSession(LOGOUT, "", user.cookie(), csrf, ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
		assertThat(cookies).anySatisfy(header -> assertThat(header).startsWith("__Host-session=").contains("Max-Age=0"));
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", user.userId().toString()))
			.isZero();
		assertThat(sessionStatus(user.cookie())).as("the old cookie no longer authenticates").isEqualTo(401);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.session.revoked' AND target_id = ?",
				user.userId())).isEqualTo(1);
	}

	@Test
	void logoutNeedsTheSessionBoundCsrfToken() throws Exception {
		LoggedIn user = login();
		LoggedIn other = login();
		String ip = freshIp();
		AuthApi.Csrf preLogin = api.csrf(ip);

		MvcResult none = api.postWithSession(LOGOUT, "", user.cookie(), null, ip);
		MvcResult garbage = api.postWithSession(LOGOUT, "", user.cookie(), "not-a-token", ip);
		MvcResult otherSessions = api.postWithSession(LOGOUT, "", user.cookie(), api.sessionCsrf(other.cookie(), ip), ip);
		MvcResult preLoginToken = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post(LOGOUT)
			.header("X-BFF-Credential", AuthApi.BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.header("X-CSRF-TOKEN", preLogin.token())
			.cookie(user.cookie(), preLogin.cookie())).andReturn();

		for (MvcResult result : List.of(none, garbage, otherSessions, preLoginToken)) {
			assertThat(result.getResponse().getStatus()).isEqualTo(403);
			assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"CSRF_REJECTED\"");
		}
		assertThat(sessionStatus(user.cookie())).as("nothing was logged out").isEqualTo(200);
	}

	@Test
	void theSessionBoundCsrfTokenIsIssuedWithoutACookieAndChangesWithTheSession() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie first = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		Cookie second = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		String ip = freshIp();

		MvcResult issued = api.get("/api/v1/auth/csrf", first, ip);
		String tokenOfFirst = AuthApi.parse(issued).path("token").asString();

		assertThat(issued.getResponse().getHeaders("Set-Cookie")).as("no double-submit cookie for a session")
			.noneSatisfy(header -> assertThat(header).startsWith("__Host-csrf"));
		assertThat(AuthApi.parse(issued).path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
		assertThat(tokenOfFirst).doesNotContain(".");
		assertThat(api.postWithSession(LOGOUT, "", second, tokenOfFirst, ip).getResponse().getStatus())
			.as("a token from one session on another")
			.isEqualTo(403);
		assertThat(api.postWithSession(LOGOUT, "", first, tokenOfFirst, ip).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void anIdleSessionExpiresAfterThirtyMinutesAndActivityKeepsItAlive() throws Exception {
		LoggedIn user = login();

		ageSession(user.userId(), Duration.ofMinutes(29), Duration.ZERO);
		assertThat(sessionStatus(user.cookie())).as("29 minutes idle").isEqualTo(200);

		ageSession(user.userId(), Duration.ofMinutes(29), Duration.ZERO);
		assertThat(sessionStatus(user.cookie())).as("the request above reset the idle clock").isEqualTo(200);

		ageSession(user.userId(), Duration.ofMinutes(31), Duration.ZERO);
		assertThat(sessionStatus(user.cookie())).as("31 minutes idle").isEqualTo(401);
	}

	@Test
	void aSessionOlderThanTwelveHoursIsRejectedHoweverActiveItWas() throws Exception {
		LoggedIn user = login();

		ageSession(user.userId(), Duration.ZERO, Duration.ofHours(11).plusMinutes(59));
		assertThat(sessionStatus(user.cookie())).as("11h59m old").isEqualTo(200);

		ageSession(user.userId(), Duration.ZERO, Duration.ofMinutes(2));
		assertThat(sessionStatus(user.cookie())).as("12h01m old, and active a moment ago").isEqualTo(401);
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", user.userId().toString()))
			.as("the expired session is destroyed")
			.isZero();
	}

	@Test
	void revokingAUserEndsEverySessionOfThatUserOnly() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie one = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		Cookie two = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		LoggedIn bystander = login();

		assertThat(revoker.revokeAll(userId)).isEqualTo(2);

		assertThat(sessionStatus(one)).isEqualTo(401);
		assertThat(sessionStatus(two)).isEqualTo(401);
		assertThat(sessionStatus(bystander.cookie())).isEqualTo(200);
		assertThat(revoker.revokeAll(userId)).as("nothing left to revoke").isZero();
	}

	@Test
	void withoutASessionTheSessionEndpointIsAProblem() throws Exception {
		MvcResult result = api.get("/api/v1/auth/session", null, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
		assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
	}

	@Test
	void aGarbageSessionCookieIsSimplyUnauthenticated() throws Exception {
		assertThat(sessionStatus(new Cookie(AuthApi.SESSION_COOKIE, "!!!not-base64!!!"))).isEqualTo(401);
		assertThat(sessionStatus(new Cookie(AuthApi.SESSION_COOKIE, ""))).isEqualTo(401);
	}

	@Test
	void verifiedOnlyEndpointsRejectAnAccountThatIsNoLongerVerified() throws Exception {
		LoggedIn user = login();
		assertThat(api.get("/api/v1/test/authenticated", null, freshIp()).getResponse().getStatus())
			.as("anonymous")
			.isEqualTo(401);
		assertThat(api.get("/api/v1/test/authenticated", user.cookie(), freshIp()).getResponse().getStatus())
			.isEqualTo(200);

		jdbc.update("UPDATE app_user SET email_verified_at = NULL WHERE id = ?", user.userId());

		assertThat(api.get("/api/v1/test/authenticated", user.cookie(), freshIp()).getResponse().getStatus())
			.isEqualTo(401);
	}

	@Test
	void rotatingTheIdentifierKeepsTheSessionButNotTheOldIdentifierOrCsrfToken() throws Exception {
		LoggedIn user = login();
		String ip = freshIp();
		String oldCsrf = api.sessionCsrf(user.cookie(), ip);

		MvcResult rotated = api.postWithSession("/api/v1/test/rotate", "", user.cookie(), oldCsrf, ip);

		assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
		Cookie fresh = sessionCookie(rotated);
		assertThat(fresh).isNotNull();
		assertThat(fresh.getValue()).isNotEqualTo(user.cookie().getValue());
		assertThat(sessionStatus(user.cookie())).as("the old identifier").isEqualTo(401);
		assertThat(sessionStatus(fresh)).as("the same session under the new identifier").isEqualTo(200);
		assertThat(api.postWithSession("/api/v1/test/rotate", "", fresh, oldCsrf, ip).getResponse().getStatus())
			.as("the old CSRF token")
			.isEqualTo(403);
		String newCsrf = api.sessionCsrf(fresh, ip);
		assertThat(newCsrf).isNotEqualTo(oldCsrf);
		assertThat(api.postWithSession("/api/v1/test/rotate", "", fresh, newCsrf, ip).getResponse().getStatus())
			.isEqualTo(200);
	}

	@Test
	void sessionsAreBoundToTheirUserNotToTheClientAddress() throws Exception {
		LoggedIn user = login();

		assertThat(api.get("/api/v1/auth/session", user.cookie(), "203.0.113.200").getResponse().getStatus())
			.isEqualTo(200);
	}

}
