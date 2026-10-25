package com.example.saas.api.tenant.ownership.application;

import java.util.UUID;

import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransfer;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransferRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lock for an ownership transfer, taken after the company row and before the memberships (D-32, D-125, D-182):
 * initiating, cancelling, accepting and expiring a transfer all lock the company first, then the transfer, inside the
 * caller's transaction, so they cannot deadlock each other or the membership and invitation changes of slices 003 and
 * 004. A lock not granted within the timeout fails with a locking exception (503).
 */
@Component
public class OwnershipTransferLocks {

	private final OwnershipTransferRepository transfers;

	private final CompanyLocks companyLocks;

	OwnershipTransferLocks(OwnershipTransferRepository transfers, CompanyLocks companyLocks) {
		this.transfers = transfers;
		this.companyLocks = companyLocks;
	}

	/**
	 * Locks one transfer of the company. A transfer of another company is reported as missing, exactly like an unknown
	 * one, so a path cannot reach across tenants (AC-OWN-22, AC-TEN-08).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public OwnershipTransfer lockTransfer(UUID companyId, UUID transferId) {
		companyLocks.limitWait();
		return transfers.lockByCompanyIdAndId(companyId, transferId).orElseThrow(CompanyNotFoundException::new);
	}

}
