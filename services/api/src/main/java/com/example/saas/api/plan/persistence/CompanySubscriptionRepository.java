package com.example.saas.api.plan.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.persistence.CrossTenantByDesign;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Subscriptions are looked up by company ID, which the caller has already checked (Q-10); there is no list-all. */
public interface CompanySubscriptionRepository extends Repository<CompanySubscription, UUID> {

	CompanySubscription save(CompanySubscription subscription);

	CompanySubscription saveAndFlush(CompanySubscription subscription);

	Optional<CompanySubscription> findById(UUID companyId);

	/**
	 * The companies whose recorded state is behind the clock, oldest first, for the sweep. The sweep is the only
	 * cross-tenant reader and it only records what time already decided.
	 */
	@CrossTenantByDesign(reason = "The sweep records time transitions of every company, one company at a time under its lock")
	@Query("select s.companyId from CompanySubscription s where (s.state = "
			+ "com.example.saas.api.plan.domain.SubscriptionState.TRIALING and s.trialEndsAt <= :now) or (s.state = "
			+ "com.example.saas.api.plan.domain.SubscriptionState.EXPIRED_READ_ONLY and s.readOnlyEndsAt <= :now) "
			+ "order by s.updatedAt")
	List<UUID> findDueForTransition(@Param("now") Instant now, org.springframework.data.domain.Pageable page);

}
