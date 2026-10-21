package com.example.saas.api.tenant.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyNameTest {

	@Test
	void trimsAndAppliesNfkc() {
		assertThat(CompanyName.normalize("  Ａcme  ")).contains("Acme");
	}

	@Test
	void rejectsNullBlankAndControlCharacters() {
		assertThat(CompanyName.normalize(null)).isEmpty();
		assertThat(CompanyName.normalize("   ")).isEmpty();
		assertThat(CompanyName.normalize("Ac\nme")).isEmpty();
		assertThat(CompanyName.normalize("Ac\u0000me")).isEmpty();
	}

	@Test
	void lengthIsCountedInCodePoints() {
		assertThat(CompanyName.normalize("a".repeat(100))).isPresent();
		assertThat(CompanyName.normalize("a".repeat(101))).isEmpty();
		// 100 supplementary characters are 200 UTF-16 units but 100 code points.
		assertThat(CompanyName.normalize("😀".repeat(100))).isPresent();
		assertThat(CompanyName.normalize("😀".repeat(101))).isEmpty();
	}

}
