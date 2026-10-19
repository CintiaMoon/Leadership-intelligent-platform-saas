package com.example.saas.api.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.saas.api.auth.AuthApi.BFF_CREDENTIAL;
import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** AC-AUTH-09 and AC-AUTH-36 for requests made before a session exists (D-75). */
class PreLoginCsrfTests extends AuthIntegrationTest {

	private static final String SIGNUP = "/api/v1/auth/signup";

	private static final String VERIFY = "/api/v1/auth/verify-email";

	private String signupBody() {
		return json("email", uniqueEmail(), "password", STRONG_PASSWORD, "displayName", "Csrf Subject");
	}

	private void assertRejectedAsCsrf(MvcResult result, String email) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
		assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"CSRF_REJECTED\"");
		if (email != null) {
			assertThat(count("SELECT count(*) FROM app_user WHERE email_normalized = ?", email)).isZero();
		}
	}

	@Test
	void csrfEndpointIssuesTheTokenAsACookieAndInTheBody() throws Exception {
		MvcResult result = mvc.perform(get("/api/v1/auth/csrf").header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", freshIp())).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		String token = AuthApi.parse(result).path("token").asString();
		assertThat(AuthApi.parse(result).path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
		String cookie = result.getResponse().getHeader("Set-Cookie");
		assertThat(cookie).startsWith("__Host-csrf=" + token)
			.contains("HttpOnly")
			.contains("Secure")
			.contains("SameSite=Lax")
			.contains("Path=/")
			.doesNotContain("Domain");
	}

	@Test
	void csrfEndpointRequiresTheBffCredential() throws Exception {
		assertThat(mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void requestWithoutAnyTokenIsRejected() throws Exception {
		String body = json("email", "no.token@example.com", "password", STRONG_PASSWORD, "displayName", "X");

		MvcResult result = mvc.perform(api.request(SIGNUP, body, freshIp(), null)).andReturn();

		assertRejectedAsCsrf(result, "no.token@example.com");
	}

	@Test
	void headerWithoutCookieIsRejected() throws Exception {
		String ip = freshIp();
		AuthApi.Csrf csrf = api.csrf(ip);

		MvcResult result = mvc.perform(post(SIGNUP).header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.header("X-CSRF-TOKEN", csrf.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(signupBody())).andReturn();

		assertRejectedAsCsrf(result, null);
	}

	@Test
	void cookieWithoutHeaderIsRejected() throws Exception {
		String ip = freshIp();
		AuthApi.Csrf csrf = api.csrf(ip);

		MvcResult result = mvc.perform(post(SIGNUP).header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.cookie(csrf.cookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content(signupBody())).andReturn();

		assertRejectedAsCsrf(result, null);
	}

	@Test
	void validTokensFromTwoDifferentIssuesDoNotMatchEachOther() throws Exception {
		String ip = freshIp();
		AuthApi.Csrf first = api.csrf(ip);
		AuthApi.Csrf second = api.csrf(ip);

		MvcResult result = mvc.perform(post(SIGNUP).header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.header("X-CSRF-TOKEN", first.token())
			.cookie(second.cookie())
			.contentType(MediaType.APPLICATION_JSON)
			.content(signupBody())).andReturn();

		assertRejectedAsCsrf(result, null);
	}

	@Test
	void aTokenSignedWithTheWrongKeyIsRejectedEvenWhenCookieAndHeaderMatch() throws Exception {
		String ip = freshIp();
		String forged = "AAAAAAAAAAAAAAAAAAAAAA." + (System.currentTimeMillis() / 1000) + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

		MvcResult result = mvc.perform(post(SIGNUP).header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.header("X-CSRF-TOKEN", forged)
			.cookie(new Cookie(AuthApi.CSRF_COOKIE, forged))
			.contentType(MediaType.APPLICATION_JSON)
			.content(signupBody())).andReturn();

		assertRejectedAsCsrf(result, null);
	}

	@Test
	void aTamperedTimestampBreaksTheSignature() throws Exception {
		String ip = freshIp();
		String token = api.csrf(ip).token();
		String[] parts = token.split("\\.");
		String tampered = parts[0] + "." + (Long.parseLong(parts[1]) + 3600) + "." + parts[2];

		MvcResult result = mvc.perform(post(SIGNUP).header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.header("X-CSRF-TOKEN", tampered)
			.cookie(new Cookie(AuthApi.CSRF_COOKIE, tampered))
			.contentType(MediaType.APPLICATION_JSON)
			.content(signupBody())).andReturn();

		assertRejectedAsCsrf(result, null);
	}

	@Test
	void aDisallowedOriginIsRejectedEvenWithAValidToken() throws Exception {
		String ip = freshIp();
		String email = uniqueEmail();
		String body = json("email", email, "password", STRONG_PASSWORD, "displayName", "Origin Test");

		MvcResult result = mvc.perform(api.request(SIGNUP, body, ip, api.csrf(ip)).header("Origin", "https://evil.example"))
			.andReturn();

		assertRejectedAsCsrf(result, email);
	}

	@Test
	void theConfiguredOriginAndAMissingOriginAreAccepted() throws Exception {
		String ip = freshIp();

		MvcResult withOrigin = mvc.perform(api.request(SIGNUP, json("email", uniqueEmail(), "password", STRONG_PASSWORD,
				"displayName", "Origin Test"), ip, api.csrf(ip)).header("Origin", "https://app.test.example"))
			.andReturn();
		MvcResult withoutOrigin = api.signup(uniqueEmail(), STRONG_PASSWORD, "Origin Test", freshIp());

		assertThat(withOrigin.getResponse().getStatus()).isEqualTo(202);
		assertThat(withoutOrigin.getResponse().getStatus()).isEqualTo(202);
	}

	@Test
	void everyPreLoginEndpointEnforcesTheCheck() throws Exception {
		for (String path : new String[] { SIGNUP, VERIFY, "/api/v1/auth/verify-email/resend", "/api/v1/auth/login" }) {
			MvcResult result = mvc.perform(api.request(path, "{}", freshIp(), null)).andReturn();
			assertThat(result.getResponse().getStatus()).as(path).isEqualTo(403);
		}
	}

	@Test
	void theBffCredentialIsCheckedBeforeTheCsrfToken() throws Exception {
		MvcResult result = mvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(signupBody()))
			.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void noSessionCookieIsEverCreated() throws Exception {
		MvcResult result = api.signup(uniqueEmail(), STRONG_PASSWORD, "No Session", freshIp());

		assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
	}

}
