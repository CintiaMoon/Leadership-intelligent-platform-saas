package com.example.saas.api.plan;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.saas.api.MutableClock;
import com.example.saas.api.MutableClockConfiguration;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.CompanyService;
import com.example.saas.api.tenant.application.CompanyView;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL, a clock the test controls, and helpers for users and companies created the way the API does. */
@Import(MutableClockConfiguration.class)
abstract class PlanTestBase extends AuthIntegrationTest {

	@Autowired
	protected MutableClock clock;

	@Autowired
	protected TransactionTemplate tx;

	@Autowired
	protected CompanyService companies;

	@BeforeEach
	void freezeTheClock() {
		clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	protected UUID newUser() {
		String email = "plan." + UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "email_verified_at, created_at, updated_at) VALUES (?, ?, 'Plan User', 'hash', now(), now(), now()) "
				+ "RETURNING id", UUID.class, email, email);
	}

	/** A company created through the service, as the API does: its Owner, its subscription and its audit rows. */
	protected CompanyView createCompany(UUID userId) {
		return companies.create(userId, "Plan Co", "203.0.113." + (int) (Math.random() * 250));
	}

	protected AuthenticatedUser session(UUID userId) {
		return new AuthenticatedUser(userId, clock.instant(), clock.instant().plus(Duration.ofHours(12)));
	}

	protected String stateOf(UUID companyId) {
		return jdbc.queryForObject("SELECT state FROM company_subscription WHERE company_id = ?", String.class,
				companyId);
	}

	protected int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	protected int transitions(UUID companyId, String reason) {
		return count("SELECT count(*) FROM audit_event WHERE event_type = 'plan.subscription.transitioned' "
				+ "AND company_id = ? AND reason_code = ?", companyId, reason);
	}

}
