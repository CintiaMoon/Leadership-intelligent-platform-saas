package com.example.saas.api.plan.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to the plan catalog. Only {@code EntitlementService} and the subscription code use it (Q-23). */
public interface PlanRepository extends Repository<Plan, UUID> {

	Optional<Plan> findById(UUID id);

	/** The highest version of the plan with this code; a new subscription uses the latest version (AC-PLAN-16). */
	@Query("select p from Plan p where p.code = :code and p.version = "
			+ "(select max(q.version) from Plan q where q.code = :code)")
	Optional<Plan> findLatestByCode(@Param("code") String code);

	@Query(value = "select value from plan_limit where plan_id = :planId and limit_key = :key", nativeQuery = true)
	Optional<Integer> findLimit(@Param("planId") UUID planId, @Param("key") String key);

	@Query(value = "select feature_key from plan_feature where plan_id = :planId order by feature_key", nativeQuery = true)
	List<String> findFeatureKeys(@Param("planId") UUID planId);

	/** Rows of (limit key, value). */
	@Query(value = "select limit_key, value from plan_limit where plan_id = :planId order by limit_key", nativeQuery = true)
	List<Object[]> findLimits(@Param("planId") UUID planId);

	@Query(value = "select count(*) > 0 from plan_feature where plan_id = :planId and feature_key = :key",
			nativeQuery = true)
	boolean hasFeature(@Param("planId") UUID planId, @Param("key") String key);

}
