package com.example.saas.api.auth.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-11 and D-55. */
class EmailAddressTest {

	@Test
	void trimsLowercasesAndKeepsTheOriginalText() {
		EmailAddress address = EmailAddress.parse("  Jane.Doe@Example.COM \t").orElseThrow();

		assertThat(address.normalized()).isEqualTo("jane.doe@example.com");
		assertThat(address.original()).isEqualTo("Jane.Doe@Example.COM");
	}

	@Test
	void plusTagsAndDotsAreNotAltered() {
		assertThat(EmailAddress.parse("a.b+news@gmail.com").orElseThrow().normalized()).isEqualTo("a.b+news@gmail.com");
		assertThat(EmailAddress.parse("ab@gmail.com").orElseThrow().normalized()).isNotEqualTo("a.b@gmail.com");
	}

	@Test
	void internationalizedDomainBecomesPunycode() {
		EmailAddress address = EmailAddress.parse("User@Bücher.example").orElseThrow();

		assertThat(address.normalized()).isEqualTo("user@xn--bcher-kva.example");
		assertThat(address.original()).isEqualTo("User@Bücher.example");
	}

	@Test
	void sameAddressInDifferentCaseNormalizesToOneKey() {
		assertThat(EmailAddress.parse("USER@EXAMPLE.COM").orElseThrow().normalized())
			.isEqualTo(EmailAddress.parse("user@example.com").orElseThrow().normalized());
	}

	@Test
	void rejectsAddressesLongerThanTheLimits() {
		String tooLong = "a".repeat(64) + "@" + ("b".repeat(60) + ".").repeat(3) + "example.com";
		assertThat(tooLong.length()).isGreaterThan(254);

		assertThat(EmailAddress.parse(tooLong)).isEmpty();
		assertThat(EmailAddress.parse("x".repeat(65) + "@example.com")).as("local part over 64").isEmpty();
	}

	@Test
	void acceptsALongButValidAddress() {
		assertThat(EmailAddress.parse("a".repeat(64) + "@" + "b".repeat(63) + ".com")).isPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "plain", "@example.com", "user@", "user@@example.com", "user@localhost",
			"user@example", "user@-example.com", "user@example-.com", "user@exa mple.com", "us er@example.com",
			"\"quoted\"@example.com", "user@example.123", "user@.example.com", "user@example..com",
			".user@example.com", "user.@example.com", "us..er@example.com", "user@example.com\u0000",
			"us\ter@example.com", "user@exam​ple.com", "user@[127.0.0.1]" })
	void rejectsMalformedAddresses(String raw) {
		assertThat(EmailAddress.parse(raw)).as(raw).isEmpty();
	}

	@Test
	void rejectsNull() {
		assertThat(EmailAddress.parse(null)).isEmpty();
	}

}
