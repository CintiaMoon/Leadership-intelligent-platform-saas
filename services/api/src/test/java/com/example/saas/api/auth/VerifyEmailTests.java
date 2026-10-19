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
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-03, 12, 37 against PostgreSQL. */
class VerifyEmailTests extends AuthIntegrationTest {

	private record Account(String email, UUID userId, String token) {
	}

	private Account register() throws Exception {
		String email = uniqueEmail();
		api.signup(email, STRONG_PASSWORD, "Verify Subject", freshIp());
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		UUID userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", UUID.class, email);
		return new Account(email, userId, token);
	}

	private boolean verified(UUID userId) {
		return jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class,
				userId);
	}

	/** The parts of a problem response that must be identical for every kind of invalid token. */
	private String shape(MvcResult result) throws Exception {
		JsonNode body = AuthApi.parse(result);
		return result.getResponse().getStatus() + "|" + result.getResponse().getContentType() + "|"
				+ body.path("type").asString() + "|" + body.path("title").asString() + "|"
				+ body.path("detail").asString() + "|" + body.path("code").asString();
	}

	@Test
	void aValidTokenVerifiesTheEmailAndIsAudited() throws Exception {
		Account account = register();
		assertThat(verified(account.userId())).isFalse();

		MvcResult result = api.verify(account.token(), freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(verified(account.userId())).isTrue();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.email.verified' AND target_id = ?",
				account.userId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM verification_token WHERE user_id = ? AND consumed_at IS NULL",
				account.userId())).isZero();
	}

	@Test
	void presentingTheSameTokenAgainIsRejectedAndChangesNothing() throws Exception {
		Account account = register();
		api.verify(account.token(), freshIp());
		Object verifiedAt = jdbc.queryForObject("SELECT email_verified_at FROM app_user WHERE id = ?", Object.class,
				account.userId());

		MvcResult replay = api.verify(account.token(), freshIp());

		assertThat(replay.getResponse().getStatus()).isEqualTo(422);
		assertThat(AuthApi.parse(replay).path("code").asString()).isEqualTo("TOKEN_INVALID");
		assertThat(jdbc.queryForObject("SELECT email_verified_at FROM app_user WHERE id = ?", Object.class,
				account.userId())).isEqualTo(verifiedAt);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.email.verified' AND target_id = ?",
				account.userId())).isEqualTo(1);
	}

	@Test
	void anExpiredTokenIsRejected() throws Exception {
		Account account = register();
		jdbc.update("UPDATE verification_token SET created_at = now() - interval '25 hours', "
				+ "expires_at = now() - interval '1 hour' WHERE user_id = ?", account.userId());

		MvcResult result = api.verify(account.token(), freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(422);
		assertThat(verified(account.userId())).isFalse();
	}

	@Test
	void aTokenIsValidUntilItsExpiryInstant() throws Exception {
		Account account = register();
		jdbc.update("UPDATE verification_token SET created_at = now() - interval '23 hours', "
				+ "expires_at = now() + interval '1 minute' WHERE user_id = ?", account.userId());

		assertThat(api.verify(account.token(), freshIp()).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void unknownMalformedExpiredAndConsumedTokensAllLookTheSame() throws Exception {
		Account consumed = register();
		api.verify(consumed.token(), freshIp());
		Account expired = register();
		jdbc.update("UPDATE verification_token SET created_at = now() - interval '25 hours', "
				+ "expires_at = now() - interval '1 hour' WHERE user_id = ?", expired.userId());

		String reference = shape(api.verify(consumed.token(), freshIp()));
		assertThat(shape(api.verify(expired.token(), freshIp()))).isEqualTo(reference);
		assertThat(shape(api.verify(TokenCodec.newToken(), freshIp()))).as("unknown").isEqualTo(reference);
		assertThat(shape(api.verify("short", freshIp()))).as("malformed").isEqualTo(reference);
		assertThat(shape(api.verify("!".repeat(43), freshIp()))).as("bad characters").isEqualTo(reference);
		assertThat(reference).startsWith("422|application/problem+json").endsWith("|TOKEN_INVALID");
	}

	@Test
	void aTokenForAnotherPurposeCannotVerifyAnEmail() throws Exception {
		Account account = register();
		String resetToken = TokenCodec.newToken();
		jdbc.update("INSERT INTO verification_token (user_id, purpose, token_hash, expires_at, created_at) "
				+ "VALUES (?, 'PASSWORD_RESET', ?, now() + interval '1 hour', now())", account.userId(),
				TokenCodec.hash(resetToken));

		MvcResult result = api.verify(resetToken, freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(422);
		assertThat(verified(account.userId())).isFalse();
		assertThat(count("SELECT count(*) FROM verification_token WHERE token_hash = ? AND consumed_at IS NULL",
				TokenCodec.hash(resetToken))).as("the reset token is untouched").isEqualTo(1);
	}

	@Test
	void concurrentAttemptsWithOneTokenHaveExactlyOneWinner() throws Exception {
		Account account = register();
		int threads = 12;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String ip = freshIp();
				Callable<Integer> attempt = () -> api.verify(account.token(), ip).getResponse().getStatus();
				futures.add(executor.submit(attempt));
			}
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses.stream().filter(status -> status == 204).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(status -> status == 422).count()).isEqualTo(threads - 1);
		assertThat(verified(account.userId())).isTrue();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'auth.email.verified' AND target_id = ?",
				account.userId())).isEqualTo(1);
	}

	@Test
	void aMissingTokenFieldIsAValidationError() throws Exception {
		MvcResult result = api.postJson("/api/v1/auth/verify-email", "{}", freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

}
