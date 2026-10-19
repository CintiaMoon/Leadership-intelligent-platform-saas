package com.example.saas.api.auth.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-40 (D-87). */
class DisplayNameTest {

	@Test
	void trimsAndAcceptsOneToOneHundredCodePoints() {
		assertThat(DisplayName.parse("  Ana Souza  ")).contains("Ana Souza");
		assertThat(DisplayName.parse("x")).contains("x");
		assertThat(DisplayName.parse("y".repeat(100))).isPresent();
		assertThat(DisplayName.parse("😀".repeat(100))).isPresent();
	}

	@Test
	void rejectsBlankTooLongAndControlCharacters() {
		assertThat(DisplayName.parse(null)).isEmpty();
		assertThat(DisplayName.parse("")).isEmpty();
		assertThat(DisplayName.parse("   ")).isEmpty();
		assertThat(DisplayName.parse("y".repeat(101))).isEmpty();
		assertThat(DisplayName.parse("bad\nname")).isEmpty();
		assertThat(DisplayName.parse("bad\u0000name")).isEmpty();
	}

}
