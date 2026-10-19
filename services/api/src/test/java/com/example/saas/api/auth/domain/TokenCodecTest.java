package com.example.saas.api.auth.domain;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** D-81. */
class TokenCodecTest {

	@Test
	void tokensAre256BitsOfUrlSafeText() {
		String token = TokenCodec.newToken();

		assertThat(token).hasSize(TokenCodec.TOKEN_LENGTH).matches("[A-Za-z0-9_-]+");
	}

	@Test
	void tokensDoNotRepeat() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			assertThat(seen.add(TokenCodec.newToken())).isTrue();
		}
	}

	@Test
	void hashIsLowercaseHexSha256AndNotTheToken() {
		String token = TokenCodec.newToken();

		assertThat(TokenCodec.hash(token)).matches("[0-9a-f]{64}").isNotEqualTo(token).isEqualTo(TokenCodec.hash(token));
		assertThat(TokenCodec.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

}
