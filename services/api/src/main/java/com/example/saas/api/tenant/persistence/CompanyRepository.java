package com.example.saas.api.tenant.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** The company table is the tenant root: it is looked up by its own id after the membership check. */
public interface CompanyRepository extends JpaRepository<Company, UUID> {

}
