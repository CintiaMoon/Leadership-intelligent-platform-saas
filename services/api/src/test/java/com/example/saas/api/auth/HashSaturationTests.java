package com.example.saas.api.auth;

import java.lang.reflect.Field;
import java.util.concurrent.Semaphore;

import com.example.saas.api.auth.domain.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-AUTH-39 through the real endpoints: with every hashing permit taken, signup, login and reset confirmation
 * answer 503 with Retry-After after the wait limit and change nothing; once a permit is free they work again.
 */
class HashSaturationTests extends AuthIntegrationTest {

	@Autowired
	PasswordHasher hasher;

	private Semaphore permits;

	private int held;

	@BeforeEach
	void takeEveryPermit() throws Exception {
		Field field = PasswordHasher.class.getDeclaredField("permits");
		field.setAccessible(true);
		permits = (Semaphore) field.get(hasher);
		held = permits.drainPermits();
		assertThat(held).as("the hasher normally has permits").isPositive();
	}

	@AfterEach
	void giveThemBack() {
		permits.release(held);
	}

	private void assertBusy(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(503);
		assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(AuthApi.parse(result).path("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
	}

	@Test
	void signupAndLoginAreRefusedAndChangeNothing() throws Exception {
		String email = uniqueEmail();
		int usersBefore = count("SELECT count(*) FROM app_user");
		int sessionsBefore = count("SELECT count(*) FROM spring_session");

		assertBusy(api.signup(email, STRONG_PASSWORD, "Busy Subject", freshIp()));
		assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(usersBefore);

		MvcResult login = api.login(email, STRONG_PASSWORD, freshIp());
		assertBusy(login);
		assertThat(sessionCookie(login)).isNull();
		assertThat(count("SELECT count(*) FROM spring_session")).isEqualTo(sessionsBefore);
	}

	@Test
	void aResetThatCouldNotBeHashedLeavesTheLinkUsable() throws Exception {
		String email = uniqueEmail();
		permits.release(held);
		registerVerified(email, STRONG_PASSWORD);
		api.requestReset(email, freshIp());
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		String hashBefore = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email_normalized = ?",
				String.class, email);
		held = permits.drainPermits();

		assertBusy(api.confirmReset(token, "a brand new passphrase 4!", freshIp()));

		assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email_normalized = ?", String.class,
				email)).as("the password did not change").isEqualTo(hashBefore);
		permits.release(held);
		held = 0;
		assertThat(api.confirmReset(token, "a brand new passphrase 4!", freshIp()).getResponse().getStatus())
			.as("the same link works once capacity is back")
			.isEqualTo(204);
	}

}
