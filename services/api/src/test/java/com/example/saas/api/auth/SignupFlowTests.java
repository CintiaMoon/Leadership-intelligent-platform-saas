package com.example.saas.api.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.domain.TokenCodec;
import com.example.saas.api.auth.email.EmailMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-01, 02, 11, 17, 18, 24, 38, 40 and D-79 over HTTP against PostgreSQL. */
class SignupFlowTests extends AuthIntegrationTest {

	private static final String SIGNUP = "/api/v1/auth/signup";

	@Test
	void signupCreatesAnUnverifiedAccountAndOnlyTheWorkerSendsTheEmail() throws Exception {
		String email = uniqueEmail();
		String ip = freshIp();

		MvcResult result = api.signup("  " + email.toUpperCase() + " ", STRONG_PASSWORD, "  Ana Souza ", ip);

		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(mail.sent()).as("nothing is sent inside the request").isEmpty();

		Map<String, Object> user = jdbc.queryForMap("SELECT * FROM app_user WHERE email_normalized = ?", email);
		assertThat(user.get("email_original")).isEqualTo(email.toUpperCase());
		assertThat(user.get("display_name")).isEqualTo("Ana Souza");
		assertThat(user.get("status")).isEqualTo("ACTIVE");
		assertThat(user.get("email_verified_at")).as("account starts unverified").isNull();
		String passwordHash = (String) user.get("password_hash");
		assertThat(passwordHash).startsWith("$argon2id$").doesNotContain(STRONG_PASSWORD);

		assertThat(drainOutbox()).isEqualTo(1);
		List<EmailMessage> sent = mail.sentTo(email.toUpperCase());
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).subject()).isEqualTo("Verify your email address");
		assertThat(sent.get(0).body()).contains("https://app.test.example/verify-email#token=");

		String token = RecordingEmailSender.tokenIn(sent.get(0));
		assertThat(token).hasSize(TokenCodec.TOKEN_LENGTH);
		assertThat(jdbc.queryForObject("SELECT token_hash FROM verification_token WHERE user_id = ?", String.class,
				user.get("id"))).isEqualTo(TokenCodec.hash(token)).isNotEqualTo(token);
		assertThat(count("SELECT count(*) FROM outbox_event WHERE payload::text LIKE ?", "%" + token + "%"))
			.as("the raw token is never stored in the outbox")
			.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::int FROM verification_token WHERE user_id = ?",
				Integer.class, user.get("id"))).as("24 hour lifetime").isEqualTo(24 * 3600);
	}

	@Test
	void registrationWritesAnAuditEventLinkedToTheRequest() throws Exception {
		String email = uniqueEmail();

		MvcResult result = api.signup(email, STRONG_PASSWORD, "Audit Subject", freshIp());

		String userId = jdbc.queryForObject("SELECT id::text FROM app_user WHERE email_normalized = ?", String.class,
				email);
		Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM audit_event WHERE target_id = ?::uuid", userId);
		assertThat(audit.get("event_type")).isEqualTo("auth.user.registered");
		assertThat(audit.get("outcome")).isEqualTo("success");
		assertThat(audit.get("actor_type")).isEqualTo("user");
		assertThat(audit.get("correlation_id")).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
	}

	@Test
	void signingUpWithAnExistingAddressIsIndistinguishableAndNotifiesTheOwner() throws Exception {
		String email = uniqueEmail();
		MvcResult first = api.signup(email, STRONG_PASSWORD, "Original Owner", freshIp());
		drainOutbox();
		mail.clear();

		MvcResult second = api.signup(email.toUpperCase(), "another strong password 42", "Impostor", freshIp());

		assertThat(second.getResponse().getStatus()).isEqualTo(first.getResponse().getStatus());
		assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		assertThat(second.getResponse().getContentType()).isEqualTo(first.getResponse().getContentType());
		assertThat(second.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(
				first.getResponse().getHeaderNames());
		assertThat(count("SELECT count(*) FROM app_user WHERE email_normalized = ?", email)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT display_name FROM app_user WHERE email_normalized = ?", String.class,
				email)).as("the existing account is untouched").isEqualTo("Original Owner");

		drainOutbox();
		assertThat(mail.sent()).hasSize(1);
		EmailMessage notice = mail.sent().get(0);
		assertThat(notice.to()).isEqualTo(email);
		assertThat(notice.subject()).isEqualTo("Someone tried to register with your email address");
		assertThat(notice.body()).doesNotContain("#token=");
	}

	@Test
	void responseForANewAddressEqualsTheResponseForAnExistingOne() throws Exception {
		MvcResult newAddress = api.signup(uniqueEmail(), STRONG_PASSWORD, "New Person", freshIp());
		String taken = uniqueEmail();
		api.signup(taken, STRONG_PASSWORD, "Taken Person", freshIp());
		MvcResult existing = api.signup(taken, STRONG_PASSWORD, "Taken Person", freshIp());

		assertThat(existing.getResponse().getStatus()).isEqualTo(newAddress.getResponse().getStatus());
		assertThat(existing.getResponse().getContentAsString()).isEqualTo(newAddress.getResponse().getContentAsString());
	}

	@Test
	void concurrentSignupsForOneAddressCreateExactlyOneAccount() throws Exception {
		String email = uniqueEmail();
		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String ip = freshIp();
				Callable<Integer> task = () -> api.signup(email, STRONG_PASSWORD, "Racer", ip).getResponse().getStatus();
				futures.add(executor.submit(task));
			}
			for (Future<Integer> future : futures) {
				assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(202);
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(count("SELECT count(*) FROM app_user WHERE email_normalized = ?", email)).isEqualTo(1);
		drainOutbox();
		long verifications = mail.sentTo(email).stream().filter(m -> m.body().contains("#token=")).count();
		assertThat(verifications).as("one verification email for the one account").isEqualTo(1);
	}

	@Test
	void invalidInputIsRejectedWithFieldErrorsAndNoAccount() throws Exception {
		int usersBefore = count("SELECT count(*) FROM app_user");
		assertFieldError(json("email", "not-an-email", "password", STRONG_PASSWORD, "displayName", "A"), "email",
				"INVALID_EMAIL");
		assertFieldError(json("email", uniqueEmail(), "password", "short1!", "displayName", "A"), "password",
				"TOO_SHORT");
		assertFieldError(json("email", uniqueEmail(), "password", "password123", "displayName", "A"), "password",
				"BREACHED_PASSWORD");
		assertFieldError(json("email", uniqueEmail(), "password", "w9Q!".repeat(32) + "x", "displayName", "A"),
				"password", "TOO_LONG");
		assertFieldError(json("email", uniqueEmail(), "password", STRONG_PASSWORD, "displayName", "   "),
				"displayName", "INVALID");
		assertFieldError(json("email", uniqueEmail(), "password", STRONG_PASSWORD, "displayName", "y".repeat(101)),
				"displayName", "INVALID");
		assertFieldError("{\"email\":\"" + uniqueEmail() + "\",\"displayName\":\"A\"}", "password", "REQUIRED");
		assertThat(count("SELECT count(*) FROM app_user")).as("no account created").isEqualTo(usersBefore);
	}

	private void assertFieldError(String body, String field, String code) throws Exception {
		String ip = freshIp();
		MvcResult result = mvc.perform(api.request(SIGNUP, body, ip, api.csrf(ip))).andReturn();
		assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
		JsonNode problem = AuthApi.parse(result);
		assertThat(problem.path("code").asString()).isEqualTo("VALIDATION_FAILED");
		boolean found = false;
		for (JsonNode error : problem.path("errors")) {
			if (error.path("field").asString().equals(field) && error.path("code").asString().equals(code)) {
				found = true;
			}
		}
		assertThat(found).as("%s -> %s in %s", field, code, problem).isTrue();
		assertThat(result.getResponse().getContentAsString()).doesNotContain(STRONG_PASSWORD);
	}

	@Test
	void passwordsOfAnyCharactersUpTo128CodePointsAreAccepted() throws Exception {
		for (String password : List.of("éèêëàâäç", "w9Q!".repeat(32),
				"😀".repeat(100), "        spaced out        ")) {
			assertThat(api.signup(uniqueEmail(), password, "Any Characters", freshIp()).getResponse().getStatus())
				.as(password)
				.isEqualTo(202);
		}
	}

	@Test
	void anAccountCreatedWithADecomposedPasswordMatchesTheComposedForm() throws Exception {
		String email = uniqueEmail();
		api.signup(email, "café-secret-phrase", "Normalized", freshIp());
		String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email_normalized = ?",
				String.class, email);

		com.example.saas.api.auth.domain.PasswordHasher hasher = new com.example.saas.api.auth.domain.PasswordHasher(
				new com.example.saas.api.auth.domain.PasswordHashProperties(512, 1, 1, 2, java.time.Duration.ofSeconds(5)));
		assertThat(hasher.matches(
				com.example.saas.api.auth.domain.PasswordPolicy.normalize("café-secret-phrase"), hash)).isTrue();
	}

	@Test
	void unknownRequestFieldsAreRejected() throws Exception {
		String ip = freshIp();
		String body = "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + STRONG_PASSWORD
				+ "\",\"displayName\":\"A\",\"status\":\"DISABLED\"}";

		MvcResult result = mvc.perform(api.request(SIGNUP, body, ip, api.csrf(ip))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void registrationIsLimitedToFivePerClientAddressPerHour() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			assertThat(api.signup(uniqueEmail(), STRONG_PASSWORD, "Limit Test", ip).getResponse().getStatus())
				.as("attempt %d", i + 1)
				.isEqualTo(202);
		}
		String sixthEmail = uniqueEmail();

		MvcResult sixth = api.signup(sixthEmail, STRONG_PASSWORD, "Limit Test", ip);

		assertThat(sixth.getResponse().getStatus()).isEqualTo(429);
		assertThat(Integer.parseInt(sixth.getResponse().getHeader("Retry-After"))).isPositive();
		assertThat(AuthApi.parse(sixth).path("code").asString()).isEqualTo("RATE_LIMITED");
		assertThat(count("SELECT count(*) FROM app_user WHERE email_normalized = ?", sixthEmail)).isZero();
	}

	@Test
	void repeatedSignupsForOneAddressStopSendingEmailAfterThreeButStayNeutral() throws Exception {
		String email = uniqueEmail();
		List<String> bodies = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			MvcResult result = api.signup(email, STRONG_PASSWORD, "Persistent", freshIp());
			assertThat(result.getResponse().getStatus()).isEqualTo(202);
			bodies.add(result.getResponse().getContentAsString());
		}

		drainOutbox();

		assertThat(bodies).containsOnly(bodies.get(0));
		assertThat(mail.sentTo(email)).as("1 verification + 2 notices, then silence").hasSize(3);
	}

}
