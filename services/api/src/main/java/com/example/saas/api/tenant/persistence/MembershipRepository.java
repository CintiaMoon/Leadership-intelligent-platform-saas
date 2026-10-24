package com.example.saas.api.tenant.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends {@link Repository}, not {@code JpaRepository}, so the unscoped {@code findById} and {@code findAll} do not
 * exist. Every query names the company, or is marked {@link CrossTenantByDesign} (D-110).
 */
public interface MembershipRepository extends Repository<Membership, UUID> {

	Membership save(Membership membership);

	/** Writes now, so a following statement sees the row. */
	Membership saveAndFlush(Membership membership);

	/** Whether the user holds an ACTIVE or SUSPENDED membership in the company (the unique index covers exactly these). */
	@Query("select count(m) > 0 from Membership m where m.companyId = :companyId and m.userId = :userId and m.status in "
			+ "(com.example.saas.api.tenant.domain.MembershipStatus.ACTIVE, "
			+ "com.example.saas.api.tenant.domain.MembershipStatus.SUSPENDED)")
	boolean hasLiveMembership(@Param("companyId") UUID companyId, @Param("userId") UUID userId);

	/** The caller's membership in one company; the per-request authorization lookup. */
	Optional<Membership> findByCompanyIdAndUserIdAndStatus(UUID companyId, UUID userId, MembershipStatus status);

	/**
	 * The user's role if they have a membership with this status. Returns a plain value, not an entity, so nothing
	 * stale enters the persistence context before the locks are taken.
	 */
	@Query("select m.role from Membership m where m.companyId = :companyId and m.userId = :userId "
			+ "and m.status = :status")
	Optional<Role> findRoleByCompanyIdAndUserIdAndStatus(@Param("companyId") UUID companyId,
			@Param("userId") UUID userId, @Param("status") MembershipStatus status);

	/** Whether an ACTIVE or SUSPENDED member of the company has this normalized address (D-26, D-150). */
	@Query("select count(m) > 0 from Membership m join UserAccount u on u.id = m.userId "
			+ "where m.companyId = :companyId and u.emailNormalized = :email and m.status in "
			+ "(com.example.saas.api.tenant.domain.MembershipStatus.ACTIVE, "
			+ "com.example.saas.api.tenant.domain.MembershipStatus.SUSPENDED)")
	boolean hasLiveMemberWithEmail(@Param("companyId") UUID companyId, @Param("email") String email);

	/** {@code SELECT ... FOR UPDATE} on one membership of the company, after the company row (D-125). Use {@code CompanyLocks}. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Membership m where m.companyId = :companyId and m.id = :id")
	Optional<Membership> lockByCompanyIdAndId(@Param("companyId") UUID companyId, @Param("id") UUID id);

	/** The company's members with their names, oldest first. Bounded by the {@code Pageable}. */
	@Query("select new com.example.saas.api.tenant.persistence.MemberRow(m.id, u.id, u.displayName, u.emailOriginal, "
			+ "m.role, m.status, m.createdAt) from Membership m join UserAccount u on u.id = m.userId "
			+ "where m.companyId = :companyId and m.status in :statuses order by m.createdAt, m.id")
	List<MemberRow> listMembers(@Param("companyId") UUID companyId,
			@Param("statuses") Collection<MembershipStatus> statuses, Pageable pageable);

	/** The caller's own memberships, to list their companies. Scoped by user, never by a client-supplied company. */
	@CrossTenantByDesign(reason = "lists the companies of the authenticated user")
	List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

}
