package com.example.saas.api.tenant.ownership;

import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.application.OwnershipEvents;
import com.example.saas.api.tenant.ownership.application.OwnershipRateLimits;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * AC-OWN-06 and plan Q-18: each change to ownership is one transaction. A failure after the rows were written (here, the
 * audit write that follows them) must undo every write, the role changes, the transfer, the outbox events and the
 * revoked invitations, and must not use up the initiation allowance. Without these tests the single-transaction claim
 * would rest on reading the code.
 */
class OwnershipTransferAtomicityTests extends OwnershipTestBase {

	@MockitoSpyBean
	private AuditLog audit;

	@Autowired
	private RateLimiter limiter;

	@Autowired
	private InvitationService invitations;

	@AfterEach
	void healTheSpy() {
		Mockito.reset(audit);
	}

	private void failTheAuditOf(String eventType) {
		// The audit bean is behind a MANDATORY transactional proxy, which also guards the stubbing call itself.
		tx.executeWithoutResult(status -> Mockito.doThrow(new IllegalStateException("audit store failed"))
			.when(audit)
			.recordChange(eq(eventType), any(), any(), any(), any(), any(), any(), any(), any(), any()));
	}

	@Test
	void aFailureAfterBothRoleChangesRollsBackTheOwnershipTheTransferTheEventsAndTheInvitationRevocation() {
		Team team = team();
		UUID forAdmin = invitations.create(session(team.owner()), team.companyId(), "late.admin@example.com", Role.ADMIN).id();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		int auditsBefore = count("SELECT count(*) FROM audit_event WHERE company_id = ?", team.companyId());

		failTheAuditOf("membership.role.changed");
		assertThatThrownBy(() -> service.accept(session(team.admin()), team.companyId(), view.id()))
			.isInstanceOf(RuntimeException.class);

		assertThat(roleOf(team.companyId(), team.owner())).as("the Owner is still the Owner").isEqualTo("OWNER:ACTIVE");
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("ADMIN:ACTIVE");
		assertThat(owners(team.companyId())).isEqualTo(1);
		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
		assertThat(events(OwnershipEvents.COMPLETED, view.id())).isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ?", team.companyId())).isEqualTo(auditsBefore);
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, forAdmin))
			.isEqualTo("PENDING");

		Mockito.reset(audit);
		service.accept(session(team.admin()), team.companyId(), view.id());
		assertThat(roleOf(team.companyId(), team.admin())).as("the same transfer still works").isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void aFailureAfterTheTransferRowWasWrittenLeavesNoTransferNoEventAndNoUsedAllowance() {
		Team team = team();

		failTheAuditOf("ownership.transfer.initiated");
		assertThatThrownBy(() -> service.initiate(session(team.owner()), team.companyId(), team.adminSeat()))
			.isInstanceOf(RuntimeException.class);

		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", team.companyId())).isZero();
		assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = ? AND payload->>'companyId' = ?",
				OwnershipEvents.INITIATED, team.companyId().toString())).isZero();
		assertThat(limiter.current(OwnershipRateLimits.INITIATED_PER_COMPANY, team.companyId().toString()).count())
			.as("only a transfer that was created counts").isZero();

		Mockito.reset(audit);
		assertThat(service.initiate(session(team.owner()), team.companyId(), team.adminSeat()).id()).isNotNull();
	}

	@Test
	void aFailureAfterTheCancellationWasWrittenLeavesTheTransferPendingAndAcceptable() {
		Team team = team();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());

		failTheAuditOf("ownership.transfer.cancelled");
		assertThatThrownBy(() -> service.cancel(session(team.owner()), team.companyId(), view.id()))
			.isInstanceOf(RuntimeException.class);

		assertThat(transferStatus(view.id())).isEqualTo("PENDING");
		assertThat(events(OwnershipEvents.CANCELLED, view.id())).isZero();
		Mockito.reset(audit);
		service.accept(session(team.admin()), team.companyId(), view.id());
		assertThat(roleOf(team.companyId(), team.admin())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void aFailureWhileTheSweepExpiresATransferLeavesItPendingForTheNextRun() {
		Team team = team();
		TransferView view = service.initiate(session(team.owner()), team.companyId(), team.adminSeat());
		clock.advance(java.time.Duration.ofDays(8));

		failTheAuditOf("ownership.transfer.expired");
		sweep.runOnce();

		assertThat(transferStatus(view.id())).as("not half expired").isEqualTo("PENDING");
		assertThat(events(OwnershipEvents.EXPIRED, view.id())).isZero();
		Mockito.reset(audit);
		for (int run = 0; run < 5 && !transferStatus(view.id()).equals("EXPIRED"); run++) {
			sweep.runOnce();
		}
		assertThat(transferStatus(view.id())).isEqualTo("EXPIRED");
	}

}
