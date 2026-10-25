package com.example.saas.api.tenant.ownership.persistence;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends {@link Repository}, not {@code JpaRepository}, so there is no unscoped {@code findById} or {@code findAll}
 * (D-110). Every query names the company.
 */
public interface OwnershipTransferRepository extends Repository<OwnershipTransfer, UUID> {

	OwnershipTransfer save(OwnershipTransfer transfer);

	/** Writes now, so a following statement (the unique index on pending rows) sees the change. */
	OwnershipTransfer saveAndFlush(OwnershipTransfer transfer);

	Optional<OwnershipTransfer> findByCompanyIdAndId(UUID companyId, UUID id);

	/** The company's pending transfer, if there is one (the partial unique index allows at most one). */
	Optional<OwnershipTransfer> findByCompanyIdAndStatus(UUID companyId, TransferStatus status);

	/** {@code SELECT ... FOR UPDATE} on one transfer of the company, after the company row (D-32). Use {@code OwnershipTransferLocks}. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from OwnershipTransfer t where t.companyId = :companyId and t.id = :id")
	Optional<OwnershipTransfer> lockByCompanyIdAndId(@Param("companyId") UUID companyId, @Param("id") UUID id);

}
