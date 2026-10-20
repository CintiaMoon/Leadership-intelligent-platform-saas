package com.example.saas.api.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretQualityTest {

	@Test
	void templateValuesAndPlaceholdersAreRefused() {
		for (String value : List.of("this_is_a_secret_that_you_should_change_before_deploying_x",
				"CHANGEME-CHANGEME-CHANGEME-CHANGEME-1234567", "replace-me-with-a-real-value-0123456789abcdef",
				"your-secret-goes-here-0123456789abcdefghij")) {
			assertThatThrownBy(() -> SecretQuality.requireNotPlaceholder("x", value))
				.as(value)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("placeholder");
		}
	}

	@Test
	void aValueWithAlmostNoVarietyIsRefused() {
		assertThatThrownBy(() -> SecretQuality.requireNotPlaceholder("x", "a".repeat(64)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("variety");
		assertThatThrownBy(() -> SecretQuality.requireNotPlaceholder("x", "ab12".repeat(16)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aGeneratedValueIsAccepted() {
		assertThatCode(() -> SecretQuality.requireNotPlaceholder("x", "q7Zr0pV2mT9xLw4Kc8NfB1uYhJd3GsEa5OiRnXk6AvM="))
			.doesNotThrowAnyException();
	}

	@Test
	void theBffCredentialsAndKeysUseTheSameCheck() {
		String placeholder = "this_is_a_secret_that_you_should_change_before_deploying_x";
		assertThatThrownBy(() -> new SecurityProperties(List.of(placeholder), List.of("10.0.0.0/8")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("placeholder");
	}

}
