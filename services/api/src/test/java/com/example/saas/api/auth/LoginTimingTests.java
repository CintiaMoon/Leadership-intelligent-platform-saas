package com.example.saas.api.auth;

import com.example.saas.api.auth.domain.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * D-80: every login failure that reaches the password check does one Argon2id verification, whether the account
 * exists or not, so response time does not reveal the difference. Throttled attempts are refused cheaply instead.
 */
class LoginTimingTests extends AuthIntegrationTest {

	@MockitoSpyBean
	PasswordHasher hasher;

	@Autowired
	com.example.saas.api.ratelimit.LoginThrottle throttle;

	private void expectOneVerification(Runnable attempt) {
		Mockito.clearInvocations(hasher);
		attempt.run();
		verify(hasher, times(1)).matches(anyString(), anyString());
	}

	private Runnable login(String email, String password, String ip) {
		return () -> {
			try {
				api.login(email, password, ip);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

	@Test
	void unknownWrongPasswordUnverifiedAndDisabledAccountsAllCostOneVerification() throws Exception {
		String known = uniqueEmail();
		registerVerified(known, STRONG_PASSWORD);
		String unverified = uniqueEmail();
		registerUnverified(unverified, STRONG_PASSWORD);
		String disabled = uniqueEmail();
		registerVerified(disabled, STRONG_PASSWORD);
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE email_normalized = ?", disabled);

		expectOneVerification(login(uniqueEmail(), STRONG_PASSWORD, freshIp()));
		expectOneVerification(login(known, "wrong password entirely 1", freshIp()));
		expectOneVerification(login(unverified, STRONG_PASSWORD, freshIp()));
		expectOneVerification(login(disabled, STRONG_PASSWORD, freshIp()));
	}

	@Test
	void aThrottledAttemptIsRefusedWithoutEvaluatingThePassword() throws Exception {
		String email = uniqueEmail();
		registerVerified(email, STRONG_PASSWORD);
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			api.login(email, "wrong password entirely 2", ip);
		}
		Mockito.clearInvocations(hasher);

		api.login(email, STRONG_PASSWORD, ip);

		verify(hasher, times(0)).matches(anyString(), anyString());
	}

}
