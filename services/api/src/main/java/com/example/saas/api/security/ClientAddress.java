package com.example.saas.api.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address used for per-IP rate limits, resolved by {@link BffTrustFilter}.
 *
 * @param address canonical text form
 * @param limitKey the value to rate-limit on: the address for IPv4, and the /64 prefix for IPv6, because
 *        one subscriber commonly controls a whole /64 and per-address limits would be trivially evaded
 */
public record ClientAddress(String address, String limitKey) {

	static final String REQUEST_ATTRIBUTE = ClientAddress.class.getName();

	private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

	private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

	/** The address resolved for this request. Throws if called on a request the filter did not process. */
	public static ClientAddress of(HttpServletRequest request) {
		Object value = request.getAttribute(REQUEST_ATTRIBUTE);
		if (value instanceof ClientAddress address) {
			return address;
		}
		throw new IllegalStateException("Client address has not been resolved for this request");
	}

	/**
	 * Parses an IP literal. Returns null for anything else, including host names, so no DNS lookup can be
	 * triggered by a forged header.
	 */
	static ClientAddress parse(String text) {
		if (text == null) {
			return null;
		}
		String value = text.trim();
		java.util.regex.Matcher v4 = IPV4.matcher(value);
		if (v4.matches()) {
			for (int i = 1; i <= 4; i++) {
				if (Integer.parseInt(v4.group(i)) > 255) {
					return null;
				}
			}
			return resolve(value, false);
		}
		if (value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches()) {
			return resolve(value, true);
		}
		return null;
	}

	private static ClientAddress resolve(String literal, boolean ipv6) {
		try {
			// A literal never triggers a lookup: IPv4 matched the strict pattern, IPv6 contains a colon.
			InetAddress inet = InetAddress.getByName(literal);
			byte[] raw = inet.getAddress();
			if (raw.length == 16) {
				// IPv4-mapped IPv6 addresses are returned by the JDK as 4 bytes, so 16 bytes is real IPv6.
				StringBuilder prefix = new StringBuilder();
				for (int i = 0; i < 8; i += 2) {
					prefix.append(String.format("%02x%02x", raw[i], raw[i + 1]));
					prefix.append(':');
				}
				return new ClientAddress(inet.getHostAddress(), prefix + "/64");
			}
			String text = inet.getHostAddress();
			return new ClientAddress(text, text);
		}
		catch (UnknownHostException ex) {
			return null;
		}
	}

}
