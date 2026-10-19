package com.example.saas.api.security;

import java.util.List;

import com.example.saas.api.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BffTrustFilterTest {

	static final String CREDENTIAL_A = "unit-test-credential-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	static final String CREDENTIAL_B = "unit-test-credential-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

	private final ProblemResponseWriter problems = new ProblemResponseWriter(JsonMapper.builder().build());

	private BffTrustFilter filter(List<String> credentials, List<String> proxies) {
		return new BffTrustFilter(new SecurityProperties(credentials, proxies), problems);
	}

	private MockHttpServletRequest request(String credential, String remoteAddr, String... forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test/ip");
		request.setRemoteAddr(remoteAddr);
		if (credential != null) {
			request.addHeader(BffTrustFilter.CREDENTIAL_HEADER, credential);
		}
		for (String value : forwardedFor) {
			request.addHeader("X-Forwarded-For", value);
		}
		return request;
	}

	/** Runs the filter and returns the resolved address, or null if the request was refused. */
	private ClientAddress run(BffTrustFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
			throws Exception {
		FilterChain chain = (req, res) -> {
		};
		filter.doFilter(request, response, chain);
		return response.getStatus() == 200 ? ClientAddress.of(request) : null;
	}

	private ClientAddress runOk(BffTrustFilter filter, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		ClientAddress address = run(filter, request, response);
		assertThat(address).as("request was refused: %s", response.getContentAsString()).isNotNull();
		return address;
	}

	@Test
	void requestWithoutCredentialIsRefusedBeforeForwardedHeadersAreRead() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		run(filter, request(null, "127.0.0.1", "203.0.113.9"), response);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
	}

	@Test
	void wrongCredentialIsRefused() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		run(filter, request(CREDENTIAL_A + "x", "127.0.0.1"), response);

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	void noConfiguredCredentialRejectsEveryApiRequest() throws Exception {
		BffTrustFilter filter = filter(List.of(), List.of("127.0.0.1/32"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		run(filter, request("", "127.0.0.1"), response);
		assertThat(response.getStatus()).isEqualTo(401);

		MockHttpServletResponse second = new MockHttpServletResponse();
		run(filter, request(CREDENTIAL_A, "127.0.0.1"), second);
		assertThat(second.getStatus()).isEqualTo(401);
	}

	@Test
	void bothCredentialsAreAcceptedDuringRotation() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A, CREDENTIAL_B), List.of("127.0.0.1/32"));

		assertThat(runOk(filter, request(CREDENTIAL_A, "127.0.0.1"))).isNotNull();
		assertThat(runOk(filter, request(CREDENTIAL_B, "127.0.0.1"))).isNotNull();
	}

	@Test
	void nonApiPathsDoNotRequireTheCredential() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
		});

		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void forwardedHeaderIsIgnoredWhenThePeerIsNotATrustedProxy() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));

		ClientAddress address = runOk(filter, request(CREDENTIAL_A, "198.51.100.7", "1.2.3.4"));

		assertThat(address.address()).isEqualTo("198.51.100.7");
	}

	@Test
	void forwardedHeaderIsUsedWhenThePeerIsATrustedProxy() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));

		assertThat(runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "203.0.113.9")).address())
			.isEqualTo("203.0.113.9");
	}

	@Test
	void entriesPrependedByTheClientAreIgnored() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));

		ClientAddress address = runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "6.6.6.6, 203.0.113.9"));

		assertThat(address.address()).isEqualTo("203.0.113.9");
	}

	@Test
	void trustedHopsAtTheEndOfTheChainAreSkipped() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32", "10.0.0.0/8"));

		ClientAddress address = runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "203.0.113.9, 10.1.2.3"));

		assertThat(address.address()).isEqualTo("203.0.113.9");
	}

	@Test
	void multipleForwardedHeaderLinesAreTreatedAsOneChain() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));

		ClientAddress address = runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "6.6.6.6", "203.0.113.9"));

		assertThat(address.address()).isEqualTo("203.0.113.9");
	}

	@Test
	void malformedForwardedEntryFallsBackToThePeerAddress() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));

		assertThat(runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "evil.example.com")).address())
			.isEqualTo("127.0.0.1");
		assertThat(runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "999.1.1.1")).address()).isEqualTo("127.0.0.1");
	}

	@Test
	void ipv6AddressesAreLimitedByTheirSlash64Prefix() throws Exception {
		BffTrustFilter filter = filter(List.of(CREDENTIAL_A), List.of("127.0.0.1/32"));

		ClientAddress first = runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
		ClientAddress second = runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "2001:db8:1:2:1:2:3:4"));
		ClientAddress other = runOk(filter, request(CREDENTIAL_A, "127.0.0.1", "2001:db8:1:3::1"));

		assertThat(first.address()).isNotEqualTo(second.address());
		assertThat(first.limitKey()).isEqualTo(second.limitKey());
		assertThat(other.limitKey()).isNotEqualTo(first.limitKey());
	}

	@Test
	void propertiesRejectShortOrTooManyCredentials() {
		assertThatThrownBy(() -> new SecurityProperties(List.of("too-short"), List.of()))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecurityProperties(List.of(CREDENTIAL_A, CREDENTIAL_B, CREDENTIAL_A + "c"), List.of()))
			.isInstanceOf(IllegalStateException.class);
		assertThat(new SecurityProperties(List.of(" ", ""), List.of()).bffCredentials()).isEmpty();
	}

}
