package com.example.saas.api.tenant.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.domain.Permission;

/**
 * An ACTIVE member whose role does not hold the permission (D-108).
 *
 * <p>
 * A denial raised while locks are held carries what is needed to audit it ({@link #pendingAudit()}). The audit row is
 * written by the exception handler, after the transaction has ended and returned its connection: writing it inside the
 * transaction needs a second connection while holding the first, which lets a burst of refused requests exhaust the
 * pool and stall the whole company (D-121, D-125).
 */
public class PermissionDeniedException extends RuntimeException {

	/** What to audit once the transaction is over. */
	public record PendingAudit(UUID userId, UUID companyId, Permission permission) {
	}

	private final transient PendingAudit pendingAudit;

	/** A denial that has already been audited (or needs no audit). */
	public PermissionDeniedException() {
		super(null, null, false, false);
		this.pendingAudit = null;
	}

	/** A denial the handler must still audit. */
	public PermissionDeniedException(UUID userId, UUID companyId, Permission permission) {
		super(null, null, false, false);
		this.pendingAudit = new PendingAudit(userId, companyId, permission);
	}

	public Optional<PendingAudit> pendingAudit() {
		return Optional.ofNullable(pendingAudit);
	}

}
