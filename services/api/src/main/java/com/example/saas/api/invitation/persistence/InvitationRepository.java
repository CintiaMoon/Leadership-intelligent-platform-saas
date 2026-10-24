package com.example.saas.api.invitation.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.tenant.persistence.CrossTenantByDesign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends {@link Repository}, not {@code JpaRepository}, so there is no unscoped {@code findById} or {@code findAll}
 * (D-110). Every query names the company, except the one lookup by token hash, which is how an invitation finds its
 * company and is marked as cross-tenant by design.
 */
public interface InvitationRepository extends Repository<Invitation, UUID> {

	Invitation save(Invitation invitation);

	Optional<Invitation> findByCompanyIdAndId(UUID companyId, UUID id);

	List<Invitation> findByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, InvitationStatus status);

	Optional<Invitation> findByCompanyIdAndEmailNormalizedAndStatus(UUID companyId, String emailNormalized,
			InvitationStatus status);

	/** {@code SELECT ... FOR UPDATE} on one invitation of the company, after the company row (D-125). Use {@code InvitationLocks}. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invitation i where i.companyId = :companyId and i.id = :id")
	Optional<Invitation> lockByCompanyIdAndId(@Param("companyId") UUID companyId, @Param("id") UUID id);

	/**
	 * The company of the pending invitation that holds this token hash, as a plain value (no entity enters the
	 * persistence context before the locks). An invitation has no company in the accept path: it is found by its token.
	 */
	@CrossTenantByDesign(reason = "an invitation is found by its token hash alone; the accept path has no company")
	@Query("select i.companyId from Invitation i where i.tokenHash = :tokenHash and i.status = "
			+ "com.example.saas.api.invitation.domain.InvitationStatus.PENDING")
	Optional<UUID> findCompanyIdByTokenHash(@Param("tokenHash") String tokenHash);

}
