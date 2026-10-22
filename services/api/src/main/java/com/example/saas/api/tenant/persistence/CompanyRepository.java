package com.example.saas.api.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The company table is the tenant root: it is looked up by its own ID after the membership check. Extends
 * {@link Repository}, not {@code JpaRepository}, so {@code findAll} and {@code deleteAll} do not exist; slice 007 adds
 * its own deletion method when it needs one.
 */
public interface CompanyRepository extends Repository<Company, UUID> {

	Company save(Company company);

	Company saveAndFlush(Company company);

	Optional<Company> findById(UUID id);

	List<Company> findAllById(Iterable<UUID> ids);

	/** {@code SELECT ... FOR UPDATE}: the first lock of every membership change (D-125). Use {@code CompanyLocks}. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Company c where c.id = :id")
	Optional<Company> lockById(@Param("id") UUID id);

}
