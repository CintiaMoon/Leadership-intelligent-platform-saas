package com.example.saas.api.auth.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-AUTH-17 and AC-AUTH-38 (D-76, D-77). */
class PasswordPolicyTest {

	private final PasswordPolicy policy = new PasswordPolicy();

	@Test
	void shorterThanEightCodePointsIsRefused() {
		assertThat(policy.check("Ab1!xyz")).contains(PasswordPolicy.Violation.TOO_SHORT);
		assertThat(policy.check("")).contains(PasswordPolicy.Violation.TOO_SHORT);
	}

	@Test
	void eightCodePointsOfAnyCharactersIsAccepted() {
		assertThat(policy.check("zq9!kwmP")).isEmpty();
		assertThat(policy.check("éèêëàâäç")).isEmpty();
		assertThat(policy.check("        x")).as("spaces are ordinary characters").isEmpty();
	}

	@Test
	void sixtyFourAndOneHundredTwentyEightCodePointsAreAccepted() {
		assertThat(policy.check("qZ7".repeat(21) + "k")).isEmpty();
		assertThat(policy.check("w9Q!".repeat(32))).isEmpty();
	}

	@Test
	void moreThanOneHundredTwentyEightCodePointsIsRefused() {
		assertThat(policy.check("w9Q!".repeat(32) + "x")).contains(PasswordPolicy.Violation.TOO_LONG);
	}

	@Test
	void lengthIsCountedInCodePointsNotUtf16Units() {
		// 128 emoji are 256 UTF-16 units but 128 code points.
		assertThat(policy.check("😀".repeat(128))).isEmpty();
		assertThat(policy.check("😀".repeat(129))).contains(PasswordPolicy.Violation.TOO_LONG);
	}

	@Test
	void commonPasswordsAreRefusedRegardlessOfCase() {
		assertThat(policy.check("password123")).contains(PasswordPolicy.Violation.BREACHED_PASSWORD);
		assertThat(policy.check("PassWord123")).contains(PasswordPolicy.Violation.BREACHED_PASSWORD);
		assertThat(policy.check("qwertyuiop")).contains(PasswordPolicy.Violation.BREACHED_PASSWORD);
	}

	@Test
	void fullWidthFormOfACommonPasswordIsRefusedAfterNormalization() {
		// Full-width "password123" normalizes (NFKC) to "password123".
		String fullWidth = "ｐａｓｓｗｏｒｄ１２３";
		assertThat(policy.check(fullWidth)).contains(PasswordPolicy.Violation.BREACHED_PASSWORD);
	}

	@Test
	void equivalentUnicodeFormsNormalizeToTheSameValue() {
		assertThat(PasswordPolicy.normalize("café-secret")).isEqualTo(PasswordPolicy.normalize("café-secret"));
	}

	@Test
	void anUncommonPasswordIsAccepted() {
		assertThat(policy.check("correct horse battery staple 7!")).isEmpty();
	}

}
