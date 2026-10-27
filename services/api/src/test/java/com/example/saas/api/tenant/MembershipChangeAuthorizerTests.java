package com.example.saas.api.tenant;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.MembershipChangeAuthorizer;
import com.example.saas.api.tenant.application.MembershipConflictException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.ChangeDecision.Outcome;
import com.example.saas.api.tenant.domain.MembershipOperation;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lock sequence and the verdicts of {@link MembershipChangeAuthorizer}, against PostgreSQL (D-124, D-125). */
class MembershipChangeAuthorizerTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private MembershipChangeAuthorizer authorizer;

	@Autowired
	private CompanyLocks locks;

	private record Member(UUID userId, UUID membershipId) {
	}

	private record Company(UUID id, Member owner, Member admin, Member agent, Member viewer) {
	}

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private Member join(UUID companyId, String role, String status) {
		UUID userId = newUser();
		UUID membershipId = jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, userId,
				role, status);
		return new Member(userId, membershipId);
	}

	private Company company() {
		return tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Auth', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			Member owner = join(id, "OWNER", "ACTIVE");
			return new Company(id, owner, join(id, "ADMIN", "ACTIVE"), join(id, "AGENT", "ACTIVE"),
					join(id, "VIEWER", "ACTIVE"));
		});
	}

	private MembershipChangeAuthorizer.Authorized authorize(Member actor, UUID companyId, UUID membershipId,
			MembershipOperation op, Role newRole) {
		return tx.execute(s -> authorizer.authorize(actor.userId(), companyId, membershipId, op, newRole));
	}

	/** Holds the company lock in another thread, runs {@code whileHeld}, and commits after {@code holdMillis}. */
	private static final class Holder implements AutoCloseable {

		private final ExecutorService pool = Executors.newSingleThreadExecutor();

		private final CountDownLatch held = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private final Future<?> work;

		Holder(TransactionTemplate tx, CompanyLocks locks, UUID companyId, Runnable whileHeld) throws Exception {
			work = pool.submit(() -> tx.executeWithoutResult(s -> {
				locks.lockCompany(companyId);
				held.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				whileHeld.run();
			}));
			assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
		}

		/** Releases the lock after a pause, from its own thread (the pool's only thread is busy holding the lock). */
		void commitSoon(long millis) {
			Thread releaser = new Thread(() -> {
				try {
					Thread.sleep(millis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				release.countDown();
			});
			releaser.setDaemon(true);
			releaser.start();
		}

		@Override
		public void close() throws Exception {
			release.countDown();
			work.get(10, TimeUnit.SECONDS);
			pool.shutdownNow();
		}

	}

	@Test
	void anAllowedChangeReturnsTheLockedRowsAndTheVerdict() {
		Company c = company();

		MembershipChangeAuthorizer.Authorized result = authorize(c.owner(), c.id(), c.agent().membershipId(),
				MembershipOperation.SUSPEND, null);

		assertThat(result.decision().outcome()).isEqualTo(Outcome.ALLOWED);
		assertThat(result.actor().getRole()).isEqualTo(Role.OWNER);
		assertThat(result.target().getId()).isEqualTo(c.agent().membershipId());
		assertThat(result.company().getId()).isEqualTo(c.id());
	}

	@Test
	void aRefusedRoleIsForbiddenAndCarriesWhatTheHandlerWillAudit() {
		Company c = company();

		assertThatThrownBy(() -> authorize(c.agent(), c.id(), c.viewer().membershipId(), MembershipOperation.SUSPEND, null))
			.isInstanceOfSatisfying(PermissionDeniedException.class, e -> assertThat(e.pendingAudit()).contains(
					new PermissionDeniedException.PendingAudit(c.agent().userId(), c.id(),
							com.example.saas.api.tenant.domain.Permission.SUSPEND_OR_REMOVE_MEMBERS)));
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'tenant.authorization.denied' "
				+ "AND company_id = ?", c.id())).as("not written inside the transaction that holds the locks").isZero();
	}

	@Test
	void anAdminActingOnAnAdminOrTheOwnerIsForbidden() {
		Company c = company();
		Member otherAdmin = tx.execute(s -> join(c.id(), "ADMIN", "ACTIVE"));

		assertThatThrownBy(() -> authorize(c.admin(), c.id(), otherAdmin.membershipId(), MembershipOperation.REMOVE, null))
			.isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> authorize(c.admin(), c.id(), c.owner().membershipId(), MembershipOperation.SUSPEND, null))
			.isInstanceOf(PermissionDeniedException.class);
	}

	@Test
	void anImpossibleChangeIsAConflictAndTheOwnerCannotBeChangedEvenByTheOwner() {
		Company c = company();
		Member suspended = tx.execute(s -> join(c.id(), "AGENT", "SUSPENDED"));

		assertThatThrownBy(() -> authorize(c.owner(), c.id(), suspended.membershipId(), MembershipOperation.SUSPEND, null))
			.isInstanceOf(MembershipConflictException.class);
		assertThatThrownBy(() -> authorize(c.owner(), c.id(), c.owner().membershipId(), MembershipOperation.REMOVE, null))
			.isInstanceOf(MembershipConflictException.class);
	}

	@Test
	void assigningOwnerThroughRoleEditingIsAValidationError() {
		Company c = company();

		assertThatThrownBy(() -> authorize(c.owner(), c.id(), c.agent().membershipId(), MembershipOperation.CHANGE_ROLE,
				Role.OWNER)).isInstanceOf(ValidationFailedException.class);
	}

	@Test
	void leavingConcernsTheCallersOwnMembershipAndTheOwnerCannotLeave() {
		Company c = company();

		MembershipChangeAuthorizer.Authorized result = authorize(c.agent(), c.id(), null, MembershipOperation.LEAVE, null);

		assertThat(result.target().getId()).isEqualTo(c.agent().membershipId());
		// A membership id sent along with LEAVE is ignored: nobody can make another member leave.
		MembershipChangeAuthorizer.Authorized ignored = authorize(c.viewer(), c.id(), c.admin().membershipId(),
				MembershipOperation.LEAVE, null);
		assertThat(ignored.target().getId()).isEqualTo(c.viewer().membershipId());
		assertThatThrownBy(() -> authorize(c.owner(), c.id(), null, MembershipOperation.LEAVE, null))
			.isInstanceOf(PermissionDeniedException.class);
	}

	@Test
	void aMembershipOfAnotherCompanyAndAnOutsiderBothLookLikeNotFound() {
		Company mine = company();
		Company theirs = company();

		assertThatThrownBy(() -> authorize(mine.owner(), mine.id(), theirs.agent().membershipId(),
				MembershipOperation.SUSPEND, null)).isInstanceOf(CompanyNotFoundException.class);
		assertThatThrownBy(() -> authorize(theirs.owner(), mine.id(), mine.agent().membershipId(),
				MembershipOperation.SUSPEND, null)).isInstanceOf(CompanyNotFoundException.class);
		Member suspendedActor = tx.execute(s -> join(mine.id(), "ADMIN", "SUSPENDED"));
		assertThatThrownBy(() -> authorize(suspendedActor, mine.id(), mine.agent().membershipId(),
				MembershipOperation.SUSPEND, null)).isInstanceOf(CompanyNotFoundException.class);
	}

	@Test
	void anOutsiderIsAnsweredAtOnceEvenWhileTheCompanyIsLockedByAnother() throws Exception {
		Company c = company();
		Member outsider = tx.execute(s -> new Member(newUser(), null));

		try (Holder holder = new Holder(tx, locks, c.id(), () -> {
		})) {
			// With the lock held and a 500 ms timeout, a request that queued would fail with a locking exception.
			assertThatThrownBy(() -> authorize(outsider, c.id(), c.agent().membershipId(), MembershipOperation.SUSPEND,
					null)).isInstanceOf(CompanyNotFoundException.class);
		}
	}

	@Test
	void anAgentAndAViewerAreRefusedWithoutQueueingForTheLockAndSoIsTheOwnerLeaving() throws Exception {
		Company c = company();

		try (Holder holder = new Holder(tx, locks, c.id(), () -> {
		})) {
			for (Member weak : new Member[] { c.agent(), c.viewer() }) {
				for (MembershipOperation op : new MembershipOperation[] { MembershipOperation.CHANGE_ROLE,
						MembershipOperation.SUSPEND, MembershipOperation.REACTIVATE, MembershipOperation.REMOVE }) {
					assertThatThrownBy(() -> authorize(weak, c.id(), c.admin().membershipId(), op, Role.AGENT))
						.as("%s by a member with no such permission", op)
						.isInstanceOf(PermissionDeniedException.class);
				}
			}
			assertThatThrownBy(() -> authorize(c.owner(), c.id(), null, MembershipOperation.LEAVE, null))
				.isInstanceOf(PermissionDeniedException.class);
		}
	}

	@Test
	void aMemberWaitsForTheLockAndThenFailsWithALockTimeout() throws Exception {
		Company c = company();

		try (Holder holder = new Holder(tx, locks, c.id(), () -> {
		})) {
			assertThatThrownBy(() -> authorize(c.owner(), c.id(), c.agent().membershipId(), MembershipOperation.SUSPEND,
					null)).isInstanceOf(PessimisticLockingFailureException.class);
		}
	}

	@Test
	void anActorSuspendedWhileWaitingForTheLockIsRefusedWithNotFound() throws Exception {
		Company c = company();

		try (Holder holder = new Holder(tx, locks, c.id(),
				() -> jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", c.admin().membershipId()))) {
			holder.commitSoon(150);
			assertThatThrownBy(() -> authorize(c.admin(), c.id(), c.agent().membershipId(), MembershipOperation.SUSPEND,
					null)).isInstanceOf(CompanyNotFoundException.class);
		}
	}

	@Test
	void anActorDemotedWhileWaitingForTheLockIsJudgedByTheNewRole() throws Exception {
		Company c = company();

		try (Holder holder = new Holder(tx, locks, c.id(),
				() -> jdbc.update("UPDATE membership SET role = 'AGENT' WHERE id = ?", c.admin().membershipId()))) {
			holder.commitSoon(150);
			// Stale state would still say ADMIN and allow this; the fresh state says AGENT and refuses it.
			assertThatThrownBy(() -> authorize(c.admin(), c.id(), c.viewer().membershipId(), MembershipOperation.SUSPEND,
					null)).isInstanceOf(PermissionDeniedException.class);
		}
	}

	@Test
	void aTargetChangedWhileWaitingForTheLockIsJudgedByItsNewState() throws Exception {
		Company c = company();

		try (Holder holder = new Holder(tx, locks, c.id(),
				() -> jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", c.agent().membershipId()))) {
			holder.commitSoon(150);
			assertThatThrownBy(() -> authorize(c.owner(), c.id(), c.agent().membershipId(), MembershipOperation.SUSPEND,
					null)).isInstanceOf(MembershipConflictException.class);
		}
	}

	@Test
	void theAuthorizerCannotRunOutsideATransaction() {
		Company c = company();

		assertThatThrownBy(() -> authorizer.authorize(c.owner().userId(), c.id(), c.agent().membershipId(),
				MembershipOperation.SUSPEND, null)).isInstanceOf(IllegalTransactionStateException.class);
	}

}
