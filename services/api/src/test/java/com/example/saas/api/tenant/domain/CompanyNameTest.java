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

	@Test
	void rejectsFormatSeparatorSurrogateAndPrivateUseCharacters() {
		assertThat(CompanyName.normalize("Acme\u202Eevil")).as("bidi override").isEmpty();
		assertThat(CompanyName.normalize("Ac\u200Bme")).as("zero-width space").isEmpty();
		assertThat(CompanyName.normalize("Ac\u2028me")).as("line separator").isEmpty();
		assertThat(CompanyName.normalize("Ac\u2029me")).as("paragraph separator").isEmpty();
		assertThat(CompanyName.normalize("Ac\uE000me")).as("private use").isEmpty();
		assertThat(CompanyName.normalize("Ac\uD800me")).as("lone surrogate").isEmpty();
		assertThat(CompanyName.normalize("\u200B")).as("only a zero-width character").isEmpty();
		assertThat(CompanyName.normalize("\u200B\u200B\u200B")).isEmpty();
	}

	@Test
	void stillAcceptsOrdinaryInternationalNames() {
		assertThat(CompanyName.normalize("Caf\u00E9 \u00DCn\u00EF")).isPresent();
		assertThat(CompanyName.normalize("\u682A\u5F0F\u4F1A\u793E Example")).isPresent();
		assertThat(CompanyName.normalize("O'Brien & Sons, Ltd.")).isPresent();
		assertThat(CompanyName.normalize("\uD83D\uDE00 Smile")).as("emoji without a joiner").isPresent();
	}

}
