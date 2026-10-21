package com.example.saas.api.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import org.springframework.data.repository.Repository;

/**
 * Extends {@link Repository}, not {@code JpaRepository}, so the unscoped {@code findById} and {@code findAll} do not
 * exist. Every query names the company, or is marked {@link CrossTenantByDesign} (D-110).
 */
public interface MembershipRepository extends Repository<Membership, UUID> {

	Membership save(Membership membership);

	/** The caller's membership in one company; the per-request authorization lookup. */
	Optional<Membership> findByCompanyIdAndUserIdAndStatus(UUID companyId, UUID userId, MembershipStatus status);

	/** The caller's own memberships, to list their companies. Scoped by user, never by a client-supplied company. */
	@CrossTenantByDesign(reason = "lists the companies of the authenticated user")
	List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

}
