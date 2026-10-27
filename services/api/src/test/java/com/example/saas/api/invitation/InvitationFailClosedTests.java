package com.example.saas.api.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.ratelimit.RateLimitPolicy;
import com.example.saas.api.ratelimit.RateLimitResult;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Creating and resending fail closed when the counter store is unavailable (D-151, ADR-004). */
@Import(InvitationFailClosedTests.SwitchableStore.class)
class InvitationFailClosedTests extends AuthIntegrationTest {

	static class Switch implements RateLimiter {

		final AtomicBoolean down = new AtomicBoolean();

		@Override
		public RateLimitResult hit(RateLimitPolicy policy, String identifier) {
			if (down.get()) {
				throw new RateLimitStoreUnavailableException(new RuntimeException("connection refused"));
			}
			return new RateLimitResult(true, 1, policy.limit(), Instant.now().plus(policy.window()));
		}

		@Override
		public RateLimitResult current(RateLimitPolicy policy, String identifier) {
			return hit(policy, identifier);
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SwitchableStore {

		@Bean
		@Primary
		Switch switchableRateLimiter() {
			return new Switch();
		}

	}

	@Autowired
	private Switch store;

	@Autowired
	private InvitationService service;

	@Autowired
	private TransactionTemplate tx;

	@AfterEach
	void restoreStore() {
		store.down.set(false);
	}

	@Test
	void creatingAndResendingAreRefusedAndChangeNothingWhileTheStoreIsDown() {
		String email = "owner." + UUID.randomUUID() + "@example.com";
		UUID[] ids = new UUID[2];
		tx.executeWithoutResult(s -> {
			ids[0] = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
					+ "password_hash, created_at, updated_at) VALUES (?, ?, 'O', 'hash', now(), now()) RETURNING id",
					UUID.class, email, email);
			ids[1] = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Down', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", ids[1], ids[0]);
		});
		Instant now = Instant.now();
		AuthenticatedUser owner = new AuthenticatedUser(ids[0], now, now.plus(Duration.ofHours(12)));
		UUID existing = service.create(owner, ids[1], "g." + UUID.randomUUID() + "@example.com", Role.AGENT).id();
		int outbox = count("SELECT count(*) FROM outbox_event WHERE event_type = 'INVITATION_EMAIL'");
		store.down.set(true);

		assertThatThrownBy(() -> service.create(owner, ids[1], "h." + UUID.randomUUID() + "@example.com", Role.AGENT))
			.isInstanceOf(RateLimitStoreUnavailableException.class);
		assertThatThrownBy(() -> service.resend(owner, ids[1], existing))
			.isInstanceOf(RateLimitStoreUnavailableException.class);

		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ?", ids[1])).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'INVITATION_EMAIL'")).isEqualTo(outbox);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'invitation.resent' AND target_id = ?",
				existing)).isZero();
	}

}
