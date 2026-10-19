package com.example.saas.api.auth;

import com.example.saas.api.auth.email.EmailMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-18 and AC-AUTH-20: resend is neutral and throttled. */
class ResendVerificationTests extends AuthIntegrationTest {

	private String registerUnverified() throws Exception {
		String email = uniqueEmail();
		api.signup(email, STRONG_PASSWORD, "Resend Subject", freshIp());
		drainOutbox();
		mail.clear();
		return email;
	}

	private long verificationMails(String email) {
		return mail.sentTo(email).stream().filter(m -> m.body().contains("#token=")).count();
	}

	@Test
	void anUnverifiedAccountGetsAFreshTokenAndTheOldOneStopsWorking() throws Exception {
		String email = uniqueEmail();
		api.signup(email, STRONG_PASSWORD, "Resend Subject", freshIp());
		drainOutbox();
		String firstToken = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));
		mail.clear();

		MvcResult result = api.resend(email, freshIp());
		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		assertThat(mail.sent()).as("nothing is sent inside the request").isEmpty();
		drainOutbox();

		EmailMessage resent = mail.sentTo(email).get(0);
		String secondToken = RecordingEmailSender.tokenIn(resent);
		assertThat(secondToken).isNotEqualTo(firstToken);
		assertThat(api.verify(firstToken, freshIp()).getResponse().getStatus()).as("old token").isEqualTo(422);
		assertThat(api.verify(secondToken, freshIp()).getResponse().getStatus()).as("new token").isEqualTo(204);
	}

	@Test
	void unknownVerifiedAndDisabledAddressesGetTheSameResponseAndNoEmail() throws Exception {
		String unverified = registerUnverified();
		MvcResult reference = api.resend(unverified, freshIp());
		drainOutbox();
		mail.clear();

		String unknown = uniqueEmail();
		String verified = registerUnverified();
		jdbc.update("UPDATE app_user SET email_verified_at = now() WHERE email_normalized = ?", verified);
		String disabled = registerUnverified();
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE email_normalized = ?", disabled);

		for (String email : new String[] { unknown, verified, disabled }) {
			MvcResult result = api.resend(email, freshIp());
			assertThat(result.getResponse().getStatus()).as(email).isEqualTo(reference.getResponse().getStatus());
			assertThat(result.getResponse().getContentAsString()).as(email)
				.isEqualTo(reference.getResponse().getContentAsString());
			assertThat(result.getResponse().getHeaderNames()).as(email)
				.containsExactlyInAnyOrderElementsOf(reference.getResponse().getHeaderNames());
		}
		drainOutbox();
		assertThat(mail.sent()).as("no email for unknown, verified, or disabled accounts").isEmpty();
	}

	@Test
	void everyAcceptedRequestQueuesTheSameWorkWhetherOrNotTheAccountExists() throws Exception {
		int before = count("SELECT count(*) FROM outbox_event WHERE event_type = 'AUTH_RESEND_VERIFICATION'");

		api.resend(uniqueEmail(), freshIp());
		api.resend(registerUnverified(), freshIp());

		assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'AUTH_RESEND_VERIFICATION'"))
			.isEqualTo(before + 2);
	}

	@Test
	void theFourthResendForOneAccountIsSilentlyNotSentButStaysNeutral() throws Exception {
		String email = registerUnverified();

		for (int i = 1; i <= 5; i++) {
			MvcResult result = api.resend(email, freshIp());
			assertThat(result.getResponse().getStatus()).as("request %d", i).isEqualTo(202);
		}
		drainOutbox();

		assertThat(verificationMails(email)).as("three sent, the rest dropped").isEqualTo(3);
	}

	@Test
	void theEleventhResendFromOneAddressIsRefusedWithRetryAfter() throws Exception {
		String ip = freshIp();
		for (int i = 1; i <= 10; i++) {
			assertThat(api.resend(uniqueEmail(), ip).getResponse().getStatus()).as("request %d", i).isEqualTo(202);
		}

		MvcResult eleventh = api.resend(uniqueEmail(), ip);

		assertThat(eleventh.getResponse().getStatus()).isEqualTo(429);
		assertThat(Integer.parseInt(eleventh.getResponse().getHeader("Retry-After"))).isPositive();
		assertThat(AuthApi.parse(eleventh).path("code").asString()).isEqualTo("RATE_LIMITED");
	}

	@Test
	void aMalformedAddressIsAValidationError() throws Exception {
		MvcResult result = api.resend("not-an-email", freshIp());

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).contains("INVALID_EMAIL");
	}

	@Test
	void resendMatchesTheNormalizedAddress() throws Exception {
		String email = registerUnverified();

		api.resend("  " + email.toUpperCase() + "  ", freshIp());
		drainOutbox();

		assertThat(verificationMails(email)).isEqualTo(1);
	}

	@Test
	void noTokenIsStoredInTheOutboxForAResend() throws Exception {
		String email = registerUnverified();
		api.resend(email, freshIp());
		drainOutbox();
		String token = RecordingEmailSender.tokenIn(mail.sentTo(email).get(0));

		assertThat(count("SELECT count(*) FROM outbox_event WHERE payload::text LIKE ?", "%" + token + "%")).isZero();
	}

}
