package com.example.saas.api.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends {@link Repository}, not {@code JpaRepository}, so the unscoped {@code findById} and {@code findAll} do not
 * exist. Every query names the company, or is marked {@link CrossTenantByDesign} (D-110).
 */
public interface MembershipRepository extends Repository<Membership, UUID> {

	Membership save(Membership membership);

	/** The caller's membership in one company; the per-request authorization lookup. */
	Optional<Membership> findByCompanyIdAndUserIdAndStatus(UUID companyId, UUID userId, MembershipStatus status);

	/** {@code SELECT ... FOR UPDATE} on one membership of the company, after the company row (D-125). Use {@code CompanyLocks}. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Membership m where m.companyId = :companyId and m.id = :id")
	Optional<Membership> lockByCompanyIdAndId(@Param("companyId") UUID companyId, @Param("id") UUID id);

	/** The caller's own memberships, to list their companies. Scoped by user, never by a client-supplied company. */
	@CrossTenantByDesign(reason = "lists the companies of the authenticated user")
	List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

}
