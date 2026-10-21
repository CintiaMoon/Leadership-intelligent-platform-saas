package com.example.saas.api.tenant.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

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

}
