package com.example.saas.api.tenant.ownership.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.auth.outbox.OutboxRepository;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.MembershipChangeListener;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.domain.TransferRules;
import com.example.saas.api.tenant.ownership.domain.TransferRules.CompletionRefusal;
import com.example.saas.api.tenant.ownership.domain.TransferRules.RecipientProblem;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransfer;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransferRepository;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactions behind {@link OwnershipTransferService}. Each one locks the company row first, then the transfer,
 * then the memberships in ID order (D-32, D-125, D-182), loads what it decides on fresh under those locks, changes the
 * rows, writes the audit rows and queues the emails, so a change either happens completely or not at all. No email is
 * sent and no network call is made inside (ADR-003): the outbox events are delivered after commit.
 *
 * <p>
 * Completion is the only place that changes who the Owner is. It demotes the old Owner and flushes, then promotes the
 * recipient and flushes, so the company has exactly one Owner membership at every point the database can see
 * (AC-OWN-05; V10's unique index and deferred trigger would refuse anything else).
 */
@Component
class OwnershipTransferTransactions {

	private final CompanyLocks companyLocks;

	private final OwnershipTransferLocks transferLocks;

	private final OwnershipTransferRepository transfers;

	private final MembershipRepository memberships;

	private final UserAccountRepository users;

	private final AuditLog audit;

	private final OutboxRepository outbox;

	private final List<MembershipChangeListener> listeners;

	private final Clock clock;

	OwnershipTransferTransactions(CompanyLocks companyLocks, OwnershipTransferLocks transferLocks,
			OwnershipTransferRepository transfers, MembershipRepository memberships, UserAccountRepository users,
			AuditLog audit, OutboxRepository outbox, List<MembershipChangeListener> listeners, Clock clock) {
		this.companyLocks = companyLocks;
		this.transferLocks = transferLocks;
		this.transfers = transfers;
		this.memberships = memberships;
		this.users = users;
		this.audit = audit;
		this.outbox = outbox;
		this.listeners = listeners;
		this.clock = clock;
	}

	// ---- initiate

	@Transactional
	TransferView initiate(AuthenticatedUser actor, UUID companyId, UUID recipientMembershipId) {
		Company company = companyLocks.lockCompany(companyId);
		Role actorRole = memberships.findRoleByCompanyIdAndUserIdAndStatus(companyId, actor.userId(), MembershipStatus.ACTIVE)
			.orElseThrow(CompanyNotFoundException::new);
		if (!CompanyPolicy.permits(actorRole, Permission.TRANSFER_OWNERSHIP, company.getStatus(), null)) {
			throw new PermissionDeniedException(actor.userId(), companyId, Permission.TRANSFER_OWNERSHIP);
		}
		Instant now = clock.instant();

		// An overdue pending transfer must not block a new one (the unique index covers pending rows): end it first.
		Optional<OwnershipTransfer> pending = transfers.findByCompanyIdAndStatus(companyId, TransferStatus.PENDING);
		if (pending.isPresent()) {
			OwnershipTransfer old = pending.get();
			if (!old.isOverdue(now)) {
				throw new OwnershipConflictException();
			}
			expireLocked(old, now);
			// Flush now: the unique index would otherwise see the new row before this update.
			transfers.saveAndFlush(old);
		}

		// An ID that is not a membership of this company is not found, like any ID of another company (AC-OWN-19).
		Membership recipient = companyLocks.lockMembership(companyId, recipientMembershipId);
		boolean self = recipient.getUserId().equals(actor.userId());
		if (self) {
			throw new ValidationFailedException(List.of(new FieldProblem("recipientMembershipId", "INVALID")));
		}
		UserAccount account = users.findById(recipient.getUserId()).orElse(null);
		RecipientProblem problem = TransferRules.checkRecipient(false, recipient.getStatus(),
				account != null && account.getStatus() == UserAccount.Status.ACTIVE,
				account != null && account.isEmailVerified());
		if (problem != RecipientProblem.NONE) {
			throw new OwnershipConflictException();
		}

		OwnershipTransfer transfer = transfers
			.saveAndFlush(OwnershipTransfer.initiate(companyId, actor.userId(), recipient.getUserId(), now));
		audit.recordChange("ownership.transfer.initiated", AuditLog.Actor.user, actor.userId(), "ownership_transfer",
				transfer.getId(), companyId, "success", null, null, "PENDING");
		queue(OwnershipEvents.INITIATED, companyId, transfer.getId(), OwnershipEvents.RECIPIENT, now);
		return view(transfer, actor.userId(), now);
	}

	// ---- cancel

	@Transactional
	TransferView cancel(AuthenticatedUser actor, UUID companyId, UUID transferId) {
		Company company = companyLocks.lockCompany(companyId);
		OwnershipTransfer transfer = transferLocks.lockTransfer(companyId, transferId);
		// Only the initiator may cancel; anybody else is told the transfer does not exist (AC-OWN-22).
		if (!transfer.getInitiatedBy().equals(actor.userId())) {
			throw new CompanyNotFoundException();
		}
		Role actorRole = memberships.findRoleByCompanyIdAndUserIdAndStatus(companyId, actor.userId(), MembershipStatus.ACTIVE)
			.orElseThrow(CompanyNotFoundException::new);
		if (!CompanyPolicy.permits(actorRole, Permission.CANCEL_OWNERSHIP_TRANSFER, company.getStatus(), null)) {
			throw new PermissionDeniedException(actor.userId(), companyId, Permission.CANCEL_OWNERSHIP_TRANSFER);
		}
		Instant now = clock.instant();
		// A transfer that is not pending, or is overdue, cannot be cancelled; the sweep ends the overdue one (AC-OWN-16).
		if (transfer.getStatus() != TransferStatus.PENDING || transfer.isOverdue(now)) {
			throw new OwnershipConflictException();
		}
		transfer.cancelByInitiator(now);
		transfers.saveAndFlush(transfer);
		audit.recordChange("ownership.transfer.cancelled", AuditLog.Actor.user, actor.userId(), "ownership_transfer",
				transfer.getId(), companyId, "success", "cancelled_by_initiator", "PENDING", "CANCELLED");
		queueBoth(OwnershipEvents.CANCELLED, companyId, transfer.getId(), now);
		return view(transfer, actor.userId(), now);
	}

	// ---- accept (completion)

	@Transactional
	TransferView accept(AuthenticatedUser actor, UUID companyId, UUID transferId) {
		Company company = companyLocks.lockCompany(companyId);
		OwnershipTransfer transfer = transferLocks.lockTransfer(companyId, transferId);
		// Only the recipient may accept; anybody else is told the transfer does not exist (AC-OWN-22).
		if (!transfer.getRecipientId().equals(actor.userId())) {
			throw new CompanyNotFoundException();
		}
		Instant now = clock.instant();

		// Both memberships, locked in ID order and loaded fresh (nothing of theirs was read before the lock).
		List<Membership> locked = memberships.lockLiveByCompanyIdAndUserIds(companyId,
				List.of(transfer.getInitiatedBy(), transfer.getRecipientId()));
		Membership initiator = of(locked, transfer.getInitiatedBy());
		Membership recipient = of(locked, transfer.getRecipientId());
		UserAccount account = users.findById(transfer.getRecipientId()).orElse(null);
		RecipientProblem problem = recipient == null ? RecipientProblem.NOT_ACTIVE_MEMBER
				: TransferRules.checkRecipient(false, recipient.getStatus(),
						account != null && account.getStatus() == UserAccount.Status.ACTIVE,
						account != null && account.isEmailVerified());
		CompletionRefusal refusal = TransferRules.completionRefusal(transfer.getStatus(), transfer.isOverdue(now),
				company.getStatus(), initiator == null ? null : initiator.getRole(),
				initiator == null ? null : initiator.getStatus(), problem);
		if (refusal != CompletionRefusal.NONE) {
			throw new OwnershipConflictException();
		}

		// Demote, flush, promote, flush: exactly one Owner membership at every committed point (AC-OWN-05).
		Role recipientBefore = recipient.getRole();
		initiator.changeRole(Role.ADMIN, now);
		memberships.saveAndFlush(initiator);
		recipient.changeRole(Role.OWNER, now);
		memberships.saveAndFlush(recipient);
		transfer.complete(now);
		transfers.saveAndFlush(transfer);

		audit.recordChange("ownership.transfer.completed", AuditLog.Actor.user, actor.userId(), "ownership_transfer",
				transfer.getId(), companyId, "success", null, "PENDING", "COMPLETED");
		audit.recordChange("membership.role.changed", AuditLog.Actor.user, actor.userId(), "membership", initiator.getId(),
				companyId, "success", "ownership_transfer", Role.OWNER.name(), Role.ADMIN.name());
		audit.recordChange("membership.role.changed", AuditLog.Actor.user, actor.userId(), "membership", recipient.getId(),
				companyId, "success", "ownership_transfer", recipientBefore.name(), Role.OWNER.name());
		// The old Owner's pending invitations for roles an Admin cannot grant are revoked in this transaction (D-189).
		notifyListeners(companyId, initiator, actor.userId());
		notifyListeners(companyId, recipient, actor.userId());
		queueBoth(OwnershipEvents.COMPLETED, companyId, transfer.getId(), now);
		return view(transfer, actor.userId(), now);
	}

	// ---- expire (the sweep)

	/**
	 * Marks one overdue pending transfer EXPIRED. Locks the company row first and re-checks under the locks, so it never
	 * inverts the lock order and two instances cannot expire the same transfer twice (D-184).
	 *
	 * @return whether this call expired it
	 */
	@Transactional
	boolean expireOne(UUID companyId, UUID transferId) {
		try {
			companyLocks.lockCompany(companyId);
		}
		catch (CompanyNotFoundException gone) {
			return false;
		}
		OwnershipTransfer transfer = transfers.lockByCompanyIdAndId(companyId, transferId).orElse(null);
		Instant now = clock.instant();
		if (transfer == null || !transfer.isOverdue(now)) {
			return false;
		}
		expireLocked(transfer, now);
		transfers.saveAndFlush(transfer);
		return true;
	}

	// ---- cancel for a deletion request (hook for slice 007)

	/**
	 * Ends the company's pending transfer because a deletion was requested (D-31, AC-OWN-09, AC-OWN-23). Runs inside the
	 * caller's transaction, which already holds the company lock. An overdue transfer is recorded as expired instead.
	 *
	 * @return whether there was a pending transfer
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean cancelPendingForDeletion(UUID companyId, UUID actorUserId) {
		OwnershipTransfer transfer = transfers.findByCompanyIdAndStatus(companyId, TransferStatus.PENDING).orElse(null);
		if (transfer == null) {
			return false;
		}
		Instant now = clock.instant();
		if (transfer.isOverdue(now)) {
			expireLocked(transfer, now);
		}
		else {
			transfer.cancelForDeletion(actorUserId, now);
			audit.recordChange("ownership.transfer.cancelled", AuditLog.Actor.user, actorUserId, "ownership_transfer",
					transfer.getId(), companyId, "success", "company_deletion", "PENDING", "CANCELLED");
			queueBoth(OwnershipEvents.CANCELLED, companyId, transfer.getId(), now);
		}
		transfers.saveAndFlush(transfer);
		return true;
	}

	// ---- reads

	@Transactional(readOnly = true)
	TransferView current(UUID callerUserId, UUID companyId) {
		OwnershipTransfer transfer = transfers.findByCompanyIdAndStatus(companyId, TransferStatus.PENDING)
			.orElseThrow(CompanyNotFoundException::new);
		return partyView(transfer, callerUserId);
	}

	@Transactional(readOnly = true)
	TransferView get(UUID callerUserId, UUID companyId, UUID transferId) {
		OwnershipTransfer transfer = transfers.findByCompanyIdAndId(companyId, transferId)
			.orElseThrow(CompanyNotFoundException::new);
		return partyView(transfer, callerUserId);
	}

	/** Only the two parties may read a transfer; everybody else is told it does not exist (AC-OWN-22). */
	private TransferView partyView(OwnershipTransfer transfer, UUID callerUserId) {
		if (!transfer.getInitiatedBy().equals(callerUserId) && !transfer.getRecipientId().equals(callerUserId)) {
			throw new CompanyNotFoundException();
		}
		return view(transfer, callerUserId, clock.instant());
	}

	// ---- helpers

	private void expireLocked(OwnershipTransfer transfer, Instant now) {
		transfer.expire(now);
		audit.recordChange("ownership.transfer.expired", AuditLog.Actor.system, null, "ownership_transfer",
				transfer.getId(), transfer.getCompanyId(), "success", null, "PENDING", "EXPIRED");
		queueBoth(OwnershipEvents.EXPIRED, transfer.getCompanyId(), transfer.getId(), now);
	}

	private static Membership of(List<Membership> locked, UUID userId) {
		return locked.stream().filter(m -> m.getUserId().equals(userId)).findFirst().orElse(null);
	}

	private void notifyListeners(UUID companyId, Membership member, UUID changedBy) {
		for (MembershipChangeListener listener : listeners) {
			listener.afterMembershipChange(companyId, member.getUserId(), member.getRole(), member.getStatus(), changedBy);
		}
	}

	private void queueBoth(String eventType, UUID companyId, UUID transferId, Instant now) {
		queue(eventType, companyId, transferId, OwnershipEvents.INITIATOR, now);
		queue(eventType, companyId, transferId, OwnershipEvents.RECIPIENT, now);
	}

	private void queue(String eventType, UUID companyId, UUID transferId, String addressee, Instant now) {
		// Identifiers only (D-185). The handler reads the company name and the address when it sends.
		outbox.enqueue(eventType,
				Map.of("companyId", companyId.toString(), "transferId", transferId.toString(), "addressee", addressee),
				"ownership-" + transferId + "-" + eventType + "-" + addressee, now);
	}

	private TransferView view(OwnershipTransfer transfer, UUID callerUserId, Instant now) {
		return new TransferView(transfer.getId(), transfer.getCompanyId(), transfer.effectiveStatus(now),
				nameOf(transfer.getInitiatedBy()), nameOf(transfer.getRecipientId()),
				transfer.getInitiatedBy().equals(callerUserId), transfer.getRecipientId().equals(callerUserId),
				transfer.getCreatedAt(), transfer.getExpiresAt(), transfer.getResolvedAt());
	}

	private String nameOf(UUID userId) {
		return users.findById(userId).map(UserAccount::getDisplayName).orElse("");
	}

}
