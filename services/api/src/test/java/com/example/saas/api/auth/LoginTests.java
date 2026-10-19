package com.example.saas.api.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.domain.PasswordHashProperties;
import com.example.saas.api.auth.domain.PasswordHasher;
import com.example.saas.api.auth.domain.PasswordPolicy;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-05, 11, 13, 18, 19, 25, 26, 27, 29 against PostgreSQL. */
class LoginTests extends AuthIntegrationTest {

	@org.springframework.beans.factory.annotation.Autowired
	com.example.saas.api.ratelimit.LoginThrottle throttle;

	private static final String WRONG_PASSWORD = "definitely not the password 99";

	/** What a caller can observe about a login failure: it must be the same for every reason. */
	private record Observed(int status, String contentType, String body, List<String> headerNames) {
	}

	private Observed observe(MvcResult result) throws Exception {
		List<String> headers = new ArrayList<>(result.getResponse().getHeaderNames());
		headers.remove("X-Request-Id");
		headers.sort(String::compareTo);
		return new Observed(result.getResponse().getStatus(), result.getResponse().getContentType(),
				result.getResponse().getContentAsString(), headers);
	}

	@Test
	void aSuccessfulLoginOpensASessionWithAHardenedCookie() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);

		MvcResult result = api.login(email, STRONG_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode body = AuthApi.parse(result);
		assertThat(body.path("userId").asString()).isEqualTo(userId.toString());
		assertThat(body.path("email").asString()).isEqualTo(email);
		assertThat(body.path("displayName").asString()).isEqualTo("Verified Person");
		assertThat(body.path("emailVerified").asBoolean()).isTrue();
		assertThat(body.path("lastAuthenticatedAt").asString()).isNotEmpty();
		assertThat(body.path("expiresAt").asString()).isNotEmpty();
		assertThat(body.has("password") || body.has("passwordHash") || body.has("sessionId")).isFalse();

		Cookie cookie = sessionCookie(result);
		assertThat(cookie).isNotNull();
		String setCookie = result.getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).startsWith("__Host-session=")
			.contains("HttpOnly")
			.contains("Secure")
			.contains("SameSite=Lax")
			.contains("Path=/")
			.doesNotContain("Domain")
			.doesNotContain("Max-Age")
			.doesNotContain("Expires");
		assertThat(result.getResponse().getContentAsString()).as("the session identifier is never in a body")
			.doesNotContain(cookie.getValue());

		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", userId.toString()))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE principal_name = ?",
				Integer.class, userId.toString())).as("30-minute idle timeout").isEqualTo(1800);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.login.succeeded' AND target_id = ?",
				userId)).isEqualTo(1);

		MvcResult session = api.get("/api/v1/auth/session", cookie, freshIp());
		assertThat(session.getResponse().getStatus()).isEqualTo(200);
		assertThat(AuthApi.parse(session).path("userId").asString()).isEqualTo(userId.toString());
	}

	@Test
	void everyLoginGetsANewSessionIdentifierAndTheOldSessionIsDestroyed() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		Cookie first = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		assertThat(api.get("/api/v1/auth/session", first, freshIp()).getResponse().getStatus()).isEqualTo(200);

		String ip = freshIp();
		MvcResult second = mvc.perform(api.request("/api/v1/auth/login", json("email", email, "password", STRONG_PASSWORD),
				ip, api.csrf(ip)).cookie(first)).andReturn();

		Cookie rotated = sessionCookie(second);
		assertThat(second.getResponse().getStatus()).isEqualTo(200);
		assertThat(rotated.getValue()).isNotEqualTo(first.getValue());
		assertThat(api.get("/api/v1/auth/session", first, freshIp()).getResponse().getStatus())
			.as("the session sent with the login is gone")
			.isEqualTo(401);
		assertThat(api.get("/api/v1/auth/session", rotated, freshIp()).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void aSessionIdentifierPlantedByAnAttackerIsNeverAdopted() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		// Built at runtime: a fake identifier chosen by an attacker, not a secret (and nothing for a scanner to flag).
		String attackerChosenId = java.util.Base64.getEncoder()
			.encodeToString("an-identifier-the-attacker-picked".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Cookie planted = new Cookie(AuthApi.SESSION_COOKIE, attackerChosenId);
		String ip = freshIp();

		MvcResult result = mvc.perform(api.request("/api/v1/auth/login", json("email", email, "password", STRONG_PASSWORD),
				ip, api.csrf(ip)).cookie(planted)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(sessionCookie(result).getValue()).isNotEqualTo(planted.getValue());
		assertThat(api.get("/api/v1/auth/session", planted, freshIp()).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void theEmailIsNormalizedAtLogin() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);

		MvcResult result = api.login("  " + email.toUpperCase() + " ", STRONG_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void theLoginPasswordIsNormalizedLikeTheSignupPassword() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, "café-secret-phrase-1");

		assertThat(api.login(email, "café-secret-phrase-1", freshIp()).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void everyFailureLooksTheSameToTheCaller() throws Exception {
		String known = uniqueEmail();
		registerVerified(known, STRONG_PASSWORD);
		String unverified = uniqueEmail();
		registerUnverified(unverified, STRONG_PASSWORD);
		String disabled = uniqueEmail();
		registerVerified(disabled, STRONG_PASSWORD);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE email_normalized = ?", disabled);
		String throttled = uniqueEmail();
		registerVerified(throttled, STRONG_PASSWORD);
		String throttledIp = freshIp();
		for (int i = 0; i < 5; i++) {
			api.login(throttled, WRONG_PASSWORD, throttledIp);
		}

		Observed reference = observe(api.login(known, WRONG_PASSWORD, freshIp()));

		assertThat(reference.status()).isEqualTo(401);
		assertThat(reference.contentType()).startsWith("application/problem+json");
		assertThat(reference.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
		assertThat(observe(api.login(uniqueEmail(), STRONG_PASSWORD, freshIp()))).as("unknown account").isEqualTo(reference);
		assertThat(observe(api.login(unverified, STRONG_PASSWORD, freshIp()))).as("unverified email, right password")
			.isEqualTo(reference);
		assertThat(observe(api.login(disabled, STRONG_PASSWORD, freshIp()))).as("disabled account, right password")
			.isEqualTo(reference);
		assertThat(observe(api.login(throttled, STRONG_PASSWORD, throttledIp))).as("throttled, right password")
			.isEqualTo(reference);
	}

	@Test
	void aDisabledOrUnverifiedAccountNeverGetsASession() throws Exception {
		String disabled = uniqueEmail();
		registerVerified(disabled, STRONG_PASSWORD);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE email_normalized = ?", disabled);
		String unverified = uniqueEmail();
		registerUnverified(unverified, STRONG_PASSWORD);

		assertThat(sessionCookie(api.login(disabled, STRONG_PASSWORD, freshIp()))).isNull();
		assertThat(sessionCookie(api.login(unverified, STRONG_PASSWORD, freshIp()))).isNull();
	}

	@Test
	void failedLoginsForExistingAccountsAreAuditedAndUnknownOnesAreNot() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		int auditBefore = count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.login.failed'");

		api.login(email, WRONG_PASSWORD, freshIp());
		api.login(uniqueEmail(), WRONG_PASSWORD, freshIp());

		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.login.failed'"))
			.isEqualTo(auditBefore + 1);
		Map<String, Object> event = jdbc.queryForMap("SELECT * FROM audit_event WHERE event_type = 'auth.login.failed' "
				+ "AND target_id = ?", userId);
		assertThat(event.get("outcome")).isEqualTo("denied");
		assertThat(event.get("reason_code")).isEqualTo("invalid_credentials");
	}

	@Test
	void malformedInputIsAValidationErrorNotALoginFailure() throws Exception {
		assertThat(api.login("not-an-email", STRONG_PASSWORD, freshIp()).getResponse().getStatus()).isEqualTo(400);
		MvcResult missing = api.postJson("/api/v1/auth/login", json("email", uniqueEmail(), "password", ""), freshIp());
		assertThat(missing.getResponse().getStatus()).isEqualTo(400);
		assertThat(missing.getResponse().getContentAsString()).contains("REQUIRED");
		assertThat(api.postJson("/api/v1/auth/login", "{\"email\":\"" + uniqueEmail() + "\"}", freshIp())
			.getResponse()
			.getStatus()).isEqualTo(400);
	}

	@Test
	void theSixthFailedAttemptFromOneAddressIsRefusedEvenWithTheRightPassword() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			assertThat(api.login(email, WRONG_PASSWORD, ip).getResponse().getStatus()).isEqualTo(401);
		}

		MvcResult sixth = api.login(email, STRONG_PASSWORD, ip);

		assertThat(sixth.getResponse().getStatus()).isEqualTo(401);
		assertThat(sessionCookie(sixth)).isNull();
	}

	@Test
	void theBlockEndsOnItsOwnAndEscalatesWithEachFurtherFailure() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			api.login(email, WRONG_PASSWORD, ip);
		}
		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus()).as("blocked").isEqualTo(401);

		ageThrottle(Duration.ofSeconds(61));
		assertThat(api.login(email, WRONG_PASSWORD, ip).getResponse().getStatus()).as("6th failure").isEqualTo(401);
		ageThrottle(Duration.ofSeconds(61));
		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus())
			.as("6th failure blocks for 2 minutes, only 61 seconds passed")
			.isEqualTo(401);
		ageThrottle(Duration.ofSeconds(61));

		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus()).as("block over").isEqualTo(200);
	}

	@Test
	void attemptsMadeWhileBlockedDoNotExtendTheBlock() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			api.login(email, WRONG_PASSWORD, ip);
		}
		for (int i = 0; i < 8; i++) {
			api.login(email, WRONG_PASSWORD, ip);
		}

		ageThrottle(Duration.ofSeconds(61));
		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus())
			.as("still one minute, not extended by the blocked attempts")
			.isEqualTo(200);
	}

	@Test
	void aSuccessfulLoginClearsTheCountForThatAddress() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		for (int i = 0; i < 4; i++) {
			api.login(email, WRONG_PASSWORD, ip);
		}
		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);

		for (int i = 0; i < 4; i++) {
			assertThat(api.login(email, WRONG_PASSWORD, ip).getResponse().getStatus()).isEqualTo(401);
		}

		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus()).as("only 4 failures since reset")
			.isEqualTo(200);
	}

	@Test
	void anotherAddressIsNotBlockedByThePairLimit() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String attackerIp = freshIp();
		for (int i = 0; i < 6; i++) {
			api.login(email, WRONG_PASSWORD, attackerIp);
		}

		assertThat(api.login(email, STRONG_PASSWORD, freshIp()).getResponse().getStatus())
			.as("the real user from a different address")
			.isEqualTo(200);
	}

	@Test
	void manyFailuresAcrossAddressesAddACappedDelayButNeverLockTheAccount() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		for (int i = 0; i < 20; i++) {
			api.login(email, WRONG_PASSWORD, freshIp());
		}

		assertThat(api.login(email, STRONG_PASSWORD, freshIp()).getResponse().getStatus())
			.as("20 failures in the hour: delayed")
			.isEqualTo(401);

		ageThrottle(Duration.ofSeconds(61));
		assertThat(api.login(email, STRONG_PASSWORD, freshIp()).getResponse().getStatus())
			.as("the delay ends by itself")
			.isEqualTo(200);
	}

	@Test
	void unknownAccountsAreThrottledExactlyLikeKnownOnes() throws Exception {
		String unknown = uniqueEmail();
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			api.login(unknown, WRONG_PASSWORD, ip);
		}

		assertThat(throttle.isBlocked(unknown, ip)).as("blocked, although no such account exists").isTrue();
		assertThat(throttle.isBlocked(unknown, freshIp())).as("another address is not blocked").isFalse();
	}

	@Test
	void theTwentyFirstLoginAttemptFromOneAddressInAMinuteIsRefused() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		for (int i = 0; i < 20; i++) {
			assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus()).as("attempt %d", i + 1)
				.isEqualTo(200);
		}

		MvcResult twentyFirst = api.login(email, STRONG_PASSWORD, ip);

		assertThat(twentyFirst.getResponse().getStatus()).isEqualTo(401);
		assertThat(sessionCookie(twentyFirst)).isNull();
	}

	@Test
	void concurrentFailuresAreAllCountedAndTheBlockHolds() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				Callable<Integer> attempt = () -> api.login(email, WRONG_PASSWORD, ip).getResponse().getStatus();
				futures.add(executor.submit(attempt));
			}
			for (Future<Integer> future : futures) {
				assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(401);
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(api.login(email, STRONG_PASSWORD, ip).getResponse().getStatus()).as("blocked after the burst")
			.isEqualTo(401);
	}

	@Test
	void concurrentLoginsForOneAccountEachGetTheirOwnSession() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		ExecutorService executor = Executors.newFixedThreadPool(6);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				String ip = freshIp();
				Callable<String> login = () -> sessionCookie(api.login(email, STRONG_PASSWORD, ip)).getValue();
				futures.add(executor.submit(login));
			}
			List<String> ids = new ArrayList<>();
			for (Future<String> future : futures) {
				ids.add(future.get(60, TimeUnit.SECONDS));
			}
			assertThat(ids).doesNotHaveDuplicates().hasSize(6);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", userId.toString()))
			.isEqualTo(6);
	}

	@Test
	void aPasswordHashMadeWithWeakerParametersIsUpgradedAtLogin() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		PasswordHasher weak = new PasswordHasher(new PasswordHashProperties(256, 1, 1, 2, Duration.ofSeconds(5)));
		String weakHash = weak.hash(PasswordPolicy.normalize(STRONG_PASSWORD));
		jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", weakHash, userId);

		assertThat(api.login(email, STRONG_PASSWORD, freshIp()).getResponse().getStatus()).isEqualTo(200);

		String upgraded = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
		assertThat(upgraded).isNotEqualTo(weakHash).contains("m=512,t=1,p=1");
		assertThat(api.login(email, STRONG_PASSWORD, freshIp()).getResponse().getStatus()).as("still works").isEqualTo(200);
		String unchanged = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
		assertThat(unchanged).as("a current hash is not rewritten").isEqualTo(upgraded);
	}

	@Test
	void aFailedLoginNeverUpgradesTheHash() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		PasswordHasher weak = new PasswordHasher(new PasswordHashProperties(256, 1, 1, 2, Duration.ofSeconds(5)));
		String weakHash = weak.hash(PasswordPolicy.normalize(STRONG_PASSWORD));
		jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", weakHash, userId);

		api.login(email, WRONG_PASSWORD, freshIp());

		assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId))
			.isEqualTo(weakHash);
	}

	@Test
	void anExistingSessionDiesWhenTheAccountIsDisabled() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		assertThat(api.get("/api/v1/auth/session", cookie, freshIp()).getResponse().getStatus()).isEqualTo(200);

		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", userId);

		assertThat(api.get("/api/v1/auth/session", cookie, freshIp()).getResponse().getStatus()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", userId.toString()))
			.as("the session is destroyed, not just ignored")
			.isZero();
	}

	@Test
	void unknownRequestFieldsAreRejected() throws Exception {
		MvcResult result = api.postJson("/api/v1/auth/login",
				"{\"email\":\"" + uniqueEmail() + "\",\"password\":\"x\",\"admin\":true}", freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
	}

}
