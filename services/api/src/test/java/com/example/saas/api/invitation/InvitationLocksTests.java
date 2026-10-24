package com.example.saas.api.invitation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.invitation.application.InvitationLocks;
import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The invitation lock after the company lock (D-125, D-161), against PostgreSQL. The test profile sets the timeout to 500 ms. */
class InvitationLocksTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private InvitationLocks invitationLocks;

	@Autowired
	private CompanyLocks companyLocks;

	@Autowired
	private InvitationRepository invitations;

	private record Seed(UUID companyId, UUID invitationId) {
	}

	private Seed seed() {
		return tx.execute(s -> {
			String email = UUID.randomUUID() + "@example.com";
			UUID inviter = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
					+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
					UUID.class, email, email);
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Lock', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", companyId, inviter);
			Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
			Invitation invitation = invitations.save(Invitation.issue(companyId, "g." + UUID.randomUUID() + "@example.com",
					"g@example.com", Role.AGENT, inviter, now, now.plus(7, ChronoUnit.DAYS)));
			return new Seed(companyId, invitation.getId());
		});
	}

	@Test
	void theInvitationIsLockedAfterTheCompanyAndSecondTransactionsTimeOutWithoutChangingAnything() throws Exception {
		Seed seed = seed();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
			companyLocks.lockCompany(seed.companyId());
			invitationLocks.lockInvitation(seed.companyId(), seed.invitationId());
			held.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		try {
			assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> tx.executeWithoutResult(s -> companyLocks.lockCompany(seed.companyId())))
				.as("the company lock comes first, so a second request queues there")
				.isInstanceOf(PessimisticLockingFailureException.class);
		}
		finally {
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			pool.shutdownNow();
		}
		UUID again = tx.execute(s -> {
			companyLocks.lockCompany(seed.companyId());
			return invitationLocks.lockInvitation(seed.companyId(), seed.invitationId()).getId();
		});
		assertThat(again).isEqualTo(seed.invitationId());
	}

	@Test
	void theLockTimeoutAppliesToTheInvitationLockToo() {
		Seed seed = seed();

		String during = tx.execute(s -> {
			invitationLocks.lockInvitation(seed.companyId(), seed.invitationId());
			return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
		});

		assertThat(during).isEqualTo("500ms");
	}

	@Test
	void anInvitationOfAnotherCompanyLooksLikeAMissingOne() {
		Seed mine = seed();
		Seed theirs = seed();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			companyLocks.lockCompany(mine.companyId());
			invitationLocks.lockInvitation(mine.companyId(), theirs.invitationId());
		})).isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> tx.executeWithoutResult(
				s -> invitationLocks.lockInvitation(mine.companyId(), UUID.randomUUID())))
			.isInstanceOf(CompanyNotFoundException.class);
	}

	@Test
	void theLockCannotBeTakenOutsideATransaction() {
		Seed seed = seed();

		assertThatThrownBy(() -> invitationLocks.lockInvitation(seed.companyId(), seed.invitationId()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

}
