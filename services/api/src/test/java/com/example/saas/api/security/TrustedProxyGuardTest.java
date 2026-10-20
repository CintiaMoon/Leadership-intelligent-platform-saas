package com.example.saas.api.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedProxyGuardTest {

	@Test
	void aDeployedProfileMayNotRunWithOnlyLoopbackTrusted() {
		assertThatThrownBy(() -> TrustedProxyGuard.check(List.of("127.0.0.1/32", "::1/128"), false))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TRUSTED_PROXY_CIDRS");
		assertThatThrownBy(() -> TrustedProxyGuard.check(List.of(), false)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aDeployedProfileWithTheBffSubnetStarts() {
		assertThatCode(() -> TrustedProxyGuard.check(List.of("10.0.4.0/24"), false)).doesNotThrowAnyException();
		assertThatCode(() -> TrustedProxyGuard.check(List.of("127.0.0.1/32", "10.0.4.0/24"), false))
			.doesNotThrowAnyException();
	}

	@Test
	void localAndTestProfilesKeepTheDefault() {
		assertThatCode(() -> TrustedProxyGuard.check(List.of("127.0.0.1/32", "::1/128"), true))
			.doesNotThrowAnyException();
	}

}
