package com.example.saas.api.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.email.EmailMessage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-18, 21, 30 to 35, 37 and D-81 against PostgreSQL. */
class PasswordResetTests extends AuthIntegrationTest {

	private static final String NEW_PASSWORD = "a brand new passphrase 2026";

	/** Requests a reset for a verified account and returns the token from the email the user would get. */
	private String resetToken(String email) throws Exception {
		mail.clear();
		api.requestReset(email, freshIp());
		drainOutbox();
		return RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
	}

	private boolean canLogin(String email, String password) throws Exception {
		return api.login(email, password, freshIp()).getResponse().getStatus() == 200;
	}

	private String passwordHash(UUID userId) {
		return jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
	}

	@Test
	void aVerifiedAccountGetsAOneHourSingleUseLinkAfterTheWorkerRuns() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);

		MvcResult result = api.requestReset(email, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(mail.sent()).as("nothing is sent inside the request").isEmpty();
		drainOutbox();

		EmailMessage message = mail.sentTo(email).get(0);
		assertThat(message.subject()).isEqualTo("Reset your password");
		assertThat(message.body()).contains("https://app.test.example/reset-password#token=");
		String token = RecordingEmailSender.tokenIn(message);
		assertThat(token).hasSize(TokenCodec.TOKEN_LENGTH);
		assertThat(jdbc.queryForObject("SELECT token_hash FROM verification_token WHERE user_id = ? "
				+ "AND purpose = 'PASSWORD_RESET'", String.class, userId)).isEqualTo(TokenCodec.hash(token));
		assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::int FROM "
				+ "verification_token WHERE user_id = ? AND purpose = 'PASSWORD_RESET'", Integer.class, userId))
			.as("one hour").isEqualTo(3600);
		assertThat(count("SELECT count(*) FROM outbox_event WHERE payload::text LIKE ?", "%" + token + "%")).isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.password.reset_requested' "
				+ "AND target_id = ?", userId)).isEqualTo(1);
	}

	@Test
	void everyAddressGetsTheSameAnswerAndOnlyEligibleAccountsGetAnEmail() throws Exception {
		String verified = uniqueEmail();
		registerVerified(verified, STRONG_PASSWORD);
		MvcResult reference = api.requestReset(verified, freshIp());
		drainOutbox();
		assertThat(mail.sentTo(verified)).hasSize(1);
		mail.clear();

		String unknown = uniqueEmail();
		String unverified = uniqueEmail();
		registerUnverified(unverified, STRONG_PASSWORD);
		String disabled = uniqueEmail();
		registerVerified(disabled, STRONG_PASSWORD);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE email_normalized = ?", disabled);

		int queuedBefore = count("SELECT count(*) FROM outbox_event WHERE event_type = 'AUTH_PASSWORD_RESET_REQUEST'");
		for (String email : new String[] { unknown, unverified, disabled }) {
			MvcResult result = api.requestReset(email, freshIp());
			assertThat(result.getResponse().getStatus()).as(email).isEqualTo(reference.getResponse().getStatus());
			assertThat(result.getResponse().getContentAsString()).as(email)
				.isEqualTo(reference.getResponse().getContentAsString());
			assertThat(result.getResponse().getHeaderNames()).as(email)
				.containsExactlyInAnyOrderElementsOf(reference.getResponse().getHeaderNames());
		}
		assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'AUTH_PASSWORD_RESET_REQUEST'"))
			.as("the same work is queued whatever the account")
			.isEqualTo(queuedBefore + 3);
		drainOutbox();
		assertThat(mail.sent()).as("no email for unknown, unverified, or disabled accounts").isEmpty();
	}

	@Test
	void theLinkSetsANewPasswordAndTheOldOneStopsWorking() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String oldHash = passwordHash(userId);
		String token = resetToken(email);

		MvcResult result = api.confirmReset(token, NEW_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(result.getResponse().getHeader("Set-Cookie")).as("a reset never logs the user in").isNull();
		assertThat(passwordHash(userId)).isNotEqualTo(oldHash).startsWith("$argon2id$");
		assertThat(canLogin(email, NEW_PASSWORD)).isTrue();
		assertThat(canLogin(email, STRONG_PASSWORD)).isFalse();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.password.reset_completed' "
				+ "AND target_id = ?", userId)).isEqualTo(1);
	}

	@Test
	void aSuccessfulResetEndsEverySessionInvalidatesOtherTokensAndSendsANotice() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie one = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		Cookie two = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		LoginSessions other = new LoginSessions();
		String token = resetToken(email);
		String strayToken = TokenCodec.newToken();
		jdbc.update("INSERT INTO verification_token (user_id, purpose, token_hash, expires_at, created_at) "
				+ "VALUES (?, 'PASSWORD_RESET', ?, now() + interval '30 minutes', now())", userId,
				TokenCodec.hash(strayToken));
		mail.clear();

		assertThat(api.confirmReset(token, NEW_PASSWORD, freshIp()).getResponse().getStatus()).isEqualTo(204);

		assertThat(api.get("/api/v1/auth/session", one, freshIp()).getResponse().getStatus()).isEqualTo(401);
		assertThat(api.get("/api/v1/auth/session", two, freshIp()).getResponse().getStatus()).isEqualTo(401);
		assertThat(other.stillValid()).as("another user's session is untouched").isTrue();
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", userId.toString())).isZero();
		assertThat(api.confirmReset(strayToken, "yet another passphrase 77", freshIp()).getResponse().getStatus())
			.as("the other outstanding token was invalidated")
			.isEqualTo(422);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.session.revoked' AND target_id = ?",
				userId)).isEqualTo(1);

		drainOutbox();
		List<EmailMessage> notices = mail.sentTo(email);
		assertThat(notices).hasSize(1);
		assertThat(notices.get(0).subject()).isEqualTo("Your password was changed");
		assertThat(notices.get(0).body()).doesNotContain("#token=").doesNotContain(NEW_PASSWORD);
	}

	/** A bystander with a live session, to show a reset only touches its own user. */
	private final class LoginSessions {

		private final Cookie cookie;

		LoginSessions() throws Exception {
			String email = uniqueEmail();
			registerVerified(email, STRONG_PASSWORD);
			this.cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		}

		boolean stillValid() throws Exception {
			return api.get("/api/v1/auth/session", cookie, freshIp()).getResponse().getStatus() == 200;
		}

	}

	@Test
	void aTokenWorksOnlyOnce() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		api.confirmReset(token, NEW_PASSWORD, freshIp());
		String hashAfterReset = passwordHash(userId);

		MvcResult replay = api.confirmReset(token, "an attacker chosen passphrase 5", freshIp());

		assertThat(replay.getResponse().getStatus()).isEqualTo(422);
		assertThat(AuthApi.parse(replay).path("code").asString()).isEqualTo("TOKEN_INVALID");
		assertThat(passwordHash(userId)).isEqualTo(hashAfterReset);
		assertThat(canLogin(email, NEW_PASSWORD)).isTrue();
	}

	@Test
	void anExpiredTokenIsRefusedAndChangesNothing() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		jdbc.update("UPDATE verification_token SET created_at = now() - interval '2 hours', "
				+ "expires_at = now() - interval '1 hour' WHERE user_id = ? AND purpose = 'PASSWORD_RESET'", userId);
		String hashBefore = passwordHash(userId);

		MvcResult result = api.confirmReset(token, NEW_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(422);
		assertThat(passwordHash(userId)).isEqualTo(hashBefore);
	}

	@Test
	void aTokenIsStillValidJustBeforeItExpires() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		jdbc.update("UPDATE verification_token SET created_at = now() - interval '59 minutes', "
				+ "expires_at = now() + interval '1 minute' WHERE user_id = ? AND purpose = 'PASSWORD_RESET'", userId);

		assertThat(api.confirmReset(token, NEW_PASSWORD, freshIp()).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void concurrentAttemptsWithOneTokenHaveExactlyOneWinner() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		int threads = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		List<String> winners = new ArrayList<>();
		int refused = 0;
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String password = "concurrent winner candidate " + i + " zq";
				String ip = freshIp();
				Callable<String> attempt = () -> api.confirmReset(token, password, ip).getResponse().getStatus() == 204
						? password : null;
				futures.add(executor.submit(attempt));
			}
			for (Future<String> future : futures) {
				String winner = future.get(90, TimeUnit.SECONDS);
				if (winner != null) {
					winners.add(winner);
				}
				else {
					refused++;
				}
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(winners).hasSize(1);
		assertThat(refused).isEqualTo(threads - 1);
		assertThat(canLogin(email, winners.get(0))).as("the winner's password is the one in force").isTrue();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.password.reset_completed' "
				+ "AND target_id = ?", userId)).isEqualTo(1);
	}

	@Test
	void aWeakNewPasswordIsRefusedAndTheTokenStaysUsable() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		String hashBefore = passwordHash(userId);

		for (String[] weak : new String[][] { { "short1!", "TOO_SHORT" }, { "password123", "BREACHED_PASSWORD" },
				{ "w9Q!".repeat(32) + "x", "TOO_LONG" } }) {
			MvcResult result = api.confirmReset(token, weak[0], freshIp());
			assertThat(result.getResponse().getStatus()).as(weak[1]).isEqualTo(400);
			assertThat(result.getResponse().getContentAsString()).contains("\"field\":\"newPassword\"")
				.contains(weak[1])
				.doesNotContain(weak[0]);
		}

		assertThat(passwordHash(userId)).isEqualTo(hashBefore);
		assertThat(api.confirmReset(token, NEW_PASSWORD, freshIp()).getResponse().getStatus())
			.as("the token was not consumed by the rejected attempts")
			.isEqualTo(204);
	}

	@Test
	void unusableTokensAllGetTheSameAnswerWhateverThePassword() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String used = resetToken(email);
		api.confirmReset(used, NEW_PASSWORD, freshIp());
		String verificationToken = TokenCodec.newToken();
		UUID userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", UUID.class, email);
		jdbc.update("INSERT INTO verification_token (user_id, purpose, token_hash, expires_at, created_at) "
				+ "VALUES (?, 'EMAIL_VERIFICATION', ?, now() + interval '1 hour', now())", userId,
				TokenCodec.hash(verificationToken));

		MvcResult reference = api.confirmReset(used, NEW_PASSWORD, freshIp());

		assertThat(reference.getResponse().getStatus()).isEqualTo(422);
		for (String token : new String[] { TokenCodec.newToken(), "short", "!".repeat(43), verificationToken }) {
			for (String password : new String[] { NEW_PASSWORD, "x", "password123" }) {
				MvcResult result = api.confirmReset(token, password, freshIp());
				assertThat(result.getResponse().getStatus()).as("%s / %s", token, password).isEqualTo(422);
				assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("TOKEN_INVALID");
				assertThat(result.getResponse().getContentAsString())
					.isEqualTo(reference.getResponse().getContentAsString());
			}
		}
	}

	@Test
	void anAccountDisabledAfterTheRequestCannotBeReset() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		String hashBefore = passwordHash(userId);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", userId);

		MvcResult result = api.confirmReset(token, NEW_PASSWORD, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(422);
		assertThat(passwordHash(userId)).isEqualTo(hashBefore);
	}

	@Test
	void theFourthRequestForOneAccountIsSilentlyNotSentButStaysNeutral() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);

		for (int i = 1; i <= 5; i++) {
			assertThat(api.requestReset(email, freshIp()).getResponse().getStatus()).as("request %d", i).isEqualTo(202);
		}
		drainOutbox();

		assertThat(mail.sentTo(email).stream().filter(m -> m.body().contains("#token=")).count())
			.as("three sent, the rest dropped")
			.isEqualTo(3);
	}

	@Test
	void theEleventhRequestFromOneAddressIsRefusedWithRetryAfter() throws Exception {
		String ip = freshIp();
		for (int i = 1; i <= 10; i++) {
			assertThat(api.requestReset(uniqueEmail(), ip).getResponse().getStatus()).as("request %d", i).isEqualTo(202);
		}

		MvcResult eleventh = api.requestReset(uniqueEmail(), ip);

		assertThat(eleventh.getResponse().getStatus()).isEqualTo(429);
		assertThat(Integer.parseInt(eleventh.getResponse().getHeader("Retry-After"))).isPositive();
		assertThat(AuthApi.parse(eleventh).path("code").asString()).isEqualTo("RATE_LIMITED");
	}

	@Test
	void theRequestMatchesTheNormalizedAddress() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);

		api.requestReset("  " + email.toUpperCase() + " ", freshIp());
		drainOutbox();

		assertThat(mail.sentTo(email)).hasSize(1);
	}

	@Test
	void malformedInputIsAValidationError() throws Exception {
		assertThat(api.requestReset("not-an-email", freshIp()).getResponse().getStatus()).isEqualTo(400);
		assertThat(api.postJson("/api/v1/auth/password-reset/confirm", "{}", freshIp()).getResponse().getStatus())
			.isEqualTo(400);
		assertThat(api.postJson("/api/v1/auth/password-reset/confirm",
				"{\"token\":\"x\",\"newPassword\":\"y\",\"role\":\"admin\"}", freshIp()).getResponse().getStatus())
			.as("unknown fields")
			.isEqualTo(400);
	}

	@Test
	void theTokenIsNeverTakenFromThePathOrTheQueryString() throws Exception {
		String email = uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		String token = resetToken(email);
		String hashBefore = passwordHash(userId);
		String ip = freshIp();

		MvcResult query = mvc.perform(api.request("/api/v1/auth/password-reset/confirm?token=" + token + "&newPassword="
				+ NEW_PASSWORD.replace(' ', '+'), "{}", ip, api.csrf(ip))).andReturn();
		MvcResult path = mvc.perform(api.request("/api/v1/auth/password-reset/confirm/" + token,
				"{\"token\":\"x\",\"newPassword\":\"y\"}", ip, api.csrf(ip))).andReturn();

		assertThat(query.getResponse().getStatus()).isEqualTo(400);
		assertThat(path.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
		assertThat(passwordHash(userId)).isEqualTo(hashBefore);
		assertThat(api.confirmReset(token, NEW_PASSWORD, freshIp()).getResponse().getStatus()).as("still unused")
			.isEqualTo(204);
	}

}
