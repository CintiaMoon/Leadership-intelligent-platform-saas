package com.example.saas.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.saas.api.common.ErrorCode;
import com.example.saas.api.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the BFF as a client and resolves the real client address (ADR-001, D-50, D-91).
 *
 * <p>Every {@code /api/} request must present a configured BFF credential. Without it the request is
 * refused before anything else runs, so forwarded headers from an arbitrary caller are never read. The
 * credential identifies the BFF only; user identity comes from the session. The client address is taken
 * from {@code X-Forwarded-For} only when the immediate peer is a configured trusted proxy, and then as the
 * rightmost address that is not itself a trusted proxy, so entries a client prepends are ignored.
 *
 * <p>This filter is created by the security configuration and is intentionally not a bean, so the servlet
 * container does not also register it.
 */
public final class BffTrustFilter extends OncePerRequestFilter {

	public static final String CREDENTIAL_HEADER = "X-BFF-Credential";

	private static final String API_PREFIX = "/api/";

	private final List<byte[]> credentialDigests;

	private final List<IpAddressMatcher> trustedProxies;

	private final ProblemResponseWriter problems;

	public BffTrustFilter(SecurityProperties properties, ProblemResponseWriter problems) {
		this.credentialDigests = properties.bffCredentials().stream().map(BffTrustFilter::digest).toList();
		this.trustedProxies = properties.trustedProxyCidrs().stream().map(IpAddressMatcher::new).toList();
		this.problems = problems;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !RequestPaths.of(request).startsWith(API_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!credentialMatches(request.getHeader(CREDENTIAL_HEADER))) {
			problems.write(request, response, ErrorCode.UNAUTHENTICATED);
			return;
		}
		request.setAttribute(ClientAddress.REQUEST_ATTRIBUTE, resolveClientAddress(request));
		chain.doFilter(request, response);
	}

	/** Compares digests in constant time and checks every configured credential, so timing reveals nothing. */
	private boolean credentialMatches(String presented) {
		if (presented == null || credentialDigests.isEmpty()) {
			return false;
		}
		byte[] presentedDigest = digest(presented);
		boolean match = false;
		for (byte[] configured : credentialDigests) {
			match |= MessageDigest.isEqual(configured, presentedDigest);
		}
		return match;
	}

	private ClientAddress resolveClientAddress(HttpServletRequest request) {
		String peer = request.getRemoteAddr();
		ClientAddress peerAddress = ClientAddress.parse(peer);
		if (peerAddress == null) {
			// Cannot happen for a real connection; fail safe with a fixed, shared bucket.
			peerAddress = ClientAddress.parse("0.0.0.0");
		}
		if (!isTrustedProxy(peer)) {
			return peerAddress;
		}
		List<String> hops = new ArrayList<>();
		for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
			for (String hop : header.split(",")) {
				hops.add(hop.trim());
			}
		}
		for (int i = hops.size() - 1; i >= 0; i--) {
			ClientAddress candidate = ClientAddress.parse(hops.get(i));
			if (candidate == null) {
				// A malformed entry cannot be attributed to anyone; fall back to the proxy's own address.
				return peerAddress;
			}
			if (!isTrustedProxy(candidate.address())) {
				return candidate;
			}
		}
		return peerAddress;
	}

	private boolean isTrustedProxy(String address) {
		for (IpAddressMatcher matcher : trustedProxies) {
			if (matcher.matches(address)) {
				return true;
			}
		}
		return false;
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
