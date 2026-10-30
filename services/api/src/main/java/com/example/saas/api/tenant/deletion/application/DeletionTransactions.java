package com.example.saas.api.tenant.deletion.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.outbox.OutboxRepository;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import com.example.saas.api.security.AuthenticatedUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.example.saas.api.tenant.deletion.application.DeletionEvents.AUDIT_CANCELLED;
import static com.example.saas.api.tenant.deletion.application.DeletionEvents.AUDIT_REQUESTED;
import static com.example.saas.api.tenant.deletion.application.DeletionEvents.EMAILS_HELD_BACK;

/**
 * The changes of a deletion request and a cancellation (SPEC-DEL, plan Q-05, Q-06), each in one transaction that takes the
 * company lock first (D-32, D-125) and decides everything again under it: the caller is still an ACTIVE Owner, the state
 * is the one the operation needs, and, for a cancellation, the deadline has not passed (AC-DEL-10). Mails are only queued
 * here, through the outbox, in the same transaction (ADR-003); nothing is sent.
 */
@Component
class DeletionTransactions {

	private final CompanyLocks companyLocks;

	private final CompanyRepository companies;

	private final MembershipRepository memberships;

	private final OwnershipTransferService transfers;

	private final AuditLog audit;

	private final OutboxRepository outbox;

	private final ApplicationEventPublisher events;

	private final JdbcClient jdbc;

	private final DeletionProperties properties;

	private final Clock clock;

	DeletionTransactions(CompanyLocks companyLocks, CompanyRepository companies, MembershipRepository memberships,
			OwnershipTransferService transfers, AuditLog audit, OutboxRepository outbox, ApplicationEventPublisher events,
			JdbcClient jdbc, DeletionProperties properties, Clock clock) {
		this.companyLocks = companyLocks;
		this.companies = companies;
		this.memberships = memberships;
		this.transfers = transfers;
		this.audit = audit;
		this.outbox = outbox;
		this.events = events;
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	DeletionView request(AuthenticatedUser actor, UUID companyId) {
		Company company = companyLocks.lockCompany(companyId);
		requireOwner(actor, companyId, Permission.REQUEST_COMPANY_DELETION);
		if (company.getStatus() != CompanyStatus.ACTIVE || company.getOwnershipResolutionAt() != null) {
			throw new DeletionConflictException();
		}
		Instant now = clock.instant();
		Instant purgeAt = now.plus(properties.gracePeriod());
		// A pending ownership transfer ends with the request, under the same company lock (D-31, AC-DEL-15).
		transfers.cancelPendingForDeletion(companyId, actor.userId());
		company.requestDeletion(actor.userId(), now, purgeAt);
		companies.saveAndFlush(company);

		boolean heldBack = emailsHeldBack(companyId, AUDIT_REQUESTED, now);
		audit.recordChange(AUDIT_REQUESTED, AuditLog.Actor.user, actor.userId(), "company", companyId, companyId, "success",
				heldBack ? EMAILS_HELD_BACK : null, CompanyStatus.ACTIVE.name(), CompanyStatus.PENDING_DELETION.name());
		if (!heldBack) {
			for (Membership seat : memberships.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE)) {
				boolean decisionMaker = seat.getRole() == Role.OWNER || seat.getRole() == Role.ADMIN;
				queue(decisionMaker ? DeletionEvents.REQUESTED : DeletionEvents.NOTICE, companyId, seat.getUserId(), now);
			}
		}
		events.publishEvent(new CompanyDeletionRequested(companyId, actor.userId(), now, purgeAt));
		return new DeletionView(company.getStatus(), now, purgeAt);
	}

	@Transactional
	DeletionView cancel(AuthenticatedUser actor, UUID companyId) {
		Company company = companyLocks.lockCompany(companyId);
		requireOwner(actor, companyId, Permission.CANCEL_COMPANY_DELETION);
		Instant now = clock.instant();
		// The state and the deadline are read and used under the same lock, so a worker or a second cancel cannot slip in.
		if (company.getStatus() != CompanyStatus.PENDING_DELETION || company.getPurgeAt() == null
				|| !now.isBefore(company.getPurgeAt())) {
			throw new DeletionConflictException();
		}
		company.cancelDeletion(now);
		companies.saveAndFlush(company);

		boolean heldBack = emailsHeldBack(companyId, AUDIT_CANCELLED, now);
		audit.recordChange(AUDIT_CANCELLED, AuditLog.Actor.user, actor.userId(), "company", companyId, companyId, "success",
				heldBack ? EMAILS_HELD_BACK : null, CompanyStatus.PENDING_DELETION.name(), CompanyStatus.ACTIVE.name());
		if (!heldBack) {
			for (Membership seat : memberships.findByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE)) {
				if (seat.getRole() == Role.OWNER || seat.getRole() == Role.ADMIN) {
					queue(DeletionEvents.CANCELLED, companyId, seat.getUserId(), now);
				}
			}
		}
		return new DeletionView(company.getStatus(), null, null);
	}

	/** The caller must still be an ACTIVE Owner of this company; a changed role between the early check and the lock ends here. */
	private void requireOwner(AuthenticatedUser actor, UUID companyId, Permission permission) {
		Membership seat = memberships.findByCompanyIdAndUserIdAndStatus(companyId, actor.userId(), MembershipStatus.ACTIVE)
			.orElseThrow(CompanyNotFoundException::new);
		if (seat.getRole() != Role.OWNER) {
			throw new PermissionDeniedException(actor.userId(), companyId, permission);
		}
	}

	/**
	 * Whether this kind of email was already sent for the company within the cooldown (plan Q-26). Each kind has its own
	 * cooldown, so a cancellation soon after a request is still announced, while a request-and-cancel loop sends at most
	 * one email of each kind per period. An event whose emails were themselves held back does not start a cooldown.
	 */
	private boolean emailsHeldBack(UUID companyId, String auditType, Instant now) {
		if (properties.emailCooldown().isZero()) {
			return false;
		}
		Integer sent = jdbc.sql("""
				SELECT count(*) FROM audit_event
				 WHERE target_type = 'company' AND target_id = :company AND event_type = :type
				   AND occurred_at > :since AND reason_code IS DISTINCT FROM :heldBack
				""")
			.param("company", companyId)
			.param("type", auditType)
			.param("since", java.time.OffsetDateTime.ofInstant(now.minus(properties.emailCooldown()), java.time.ZoneOffset.UTC))
			.param("heldBack", EMAILS_HELD_BACK)
			.query(Integer.class)
			.single();
		return sent != null && sent > 0;
	}

	/** One outbox row per recipient. The payload names the company and the person only; the text is built when it is sent. */
	private void queue(String eventType, UUID companyId, UUID userId, Instant at) {
		String key = eventType + ":" + companyId + ":" + at.toEpochMilli() + ":" + userId;
		outbox.enqueue(eventType, Map.of("companyId", companyId.toString(), "userId", userId.toString()), key, at);
	}

}
