package com.example.saas.api.auth;

import java.util.Random;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test client for the authentication endpoints, speaking as the BFF would. */
public final class AuthApi {

	public static final String BFF_CREDENTIAL = "test-bff-credential-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	public static final String CSRF_COOKIE = "__Host-csrf";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Random RANDOM = new Random();

	private final MockMvc mvc;

	public AuthApi(MockMvc mvc) {
		this.mvc = mvc;
	}

	/** A client address no other test uses, so IP-based limits never collide across tests. */
	public static String freshIp() {
		return "198.18." + RANDOM.nextInt(256) + "." + (1 + RANDOM.nextInt(254));
	}

	public record Csrf(String token, Cookie cookie) {
	}

	public Csrf csrf(String ip) throws Exception {
		MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf")
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)).andReturn();
		JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
		String token = body.path("token").asString();
		return new Csrf(token, new Cookie(CSRF_COOKIE, token));
	}

	public MockHttpServletRequestBuilder request(String path, String body, String ip, Csrf csrf) {
		MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (csrf != null) {
			builder.header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie());
		}
		return builder;
	}

	/** POSTs JSON with a freshly issued, valid CSRF token. */
	public MvcResult postJson(String path, String body, String ip) throws Exception {
		return mvc.perform(request(path, body, ip, csrf(ip))).andReturn();
	}

	public MvcResult signup(String email, String password, String displayName, String ip) throws Exception {
		return postJson("/api/v1/auth/signup", json("email", email, "password", password, "displayName", displayName), ip);
	}

	public MvcResult verify(String token, String ip) throws Exception {
		return postJson("/api/v1/auth/verify-email", json("token", token), ip);
	}

	public static final String SESSION_COOKIE = "__Host-session";

	public MvcResult login(String email, String password, String ip) throws Exception {
		return postJson("/api/v1/auth/login", json("email", email, "password", password), ip);
	}

	/** The session cookie set by a response, or null. */
	public static Cookie sessionCookie(MvcResult result) {
		return result.getResponse().getCookie(SESSION_COOKIE);
	}

	/** A request carrying a session cookie, as the BFF would forward it. */
	public MvcResult get(String path, Cookie session, String ip) throws Exception {
		MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.get(path)
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip);
		if (session != null) {
			builder.cookie(session);
		}
		return mvc.perform(builder).andReturn();
	}

	/** The CSRF token bound to a session. */
	public String sessionCsrf(Cookie session, String ip) throws Exception {
		return parse(get("/api/v1/auth/csrf", session, ip)).path("token").asString();
	}

	/** A state-changing request on a session, with the given CSRF token (or none). */
	public MvcResult postWithSession(String path, String body, Cookie session, String csrfToken, String ip)
			throws Exception {
		MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post(path)
			.header("X-BFF-Credential", BFF_CREDENTIAL)
			.header("X-Forwarded-For", ip)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (session != null) {
			builder.cookie(session);
		}
		if (csrfToken != null) {
			builder.header("X-CSRF-TOKEN", csrfToken);
		}
		return mvc.perform(builder).andReturn();
	}

	public MvcResult requestReset(String email, String ip) throws Exception {
		return postJson("/api/v1/auth/password-reset/request", json("email", email), ip);
	}

	public MvcResult confirmReset(String token, String newPassword, String ip) throws Exception {
		return postJson("/api/v1/auth/password-reset/confirm", json("token", token, "newPassword", newPassword), ip);
	}

	public MvcResult resend(String email, String ip) throws Exception {
		return postJson("/api/v1/auth/verify-email/resend", json("email", email), ip);
	}

	public static String json(String... keysAndValues) {
		var node = JSON.createObjectNode();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			node.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		return node.toString();
	}

	public static JsonNode parse(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	public static String uniqueEmail() {
		return "user." + java.util.UUID.randomUUID() + "@example.com";
	}

	public static final String STRONG_PASSWORD = "correct horse battery staple 7!";

}
