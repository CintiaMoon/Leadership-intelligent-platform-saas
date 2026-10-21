package com.example.saas.api.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every query names the company or the user, never an unscoped id (D-110). */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

	/** The caller's membership in one company; the per-request authorization lookup. */
	Optional<Membership> findByCompanyIdAndUserIdAndStatus(UUID companyId, UUID userId, MembershipStatus status);

	List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

}
