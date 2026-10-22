package com.example.saas.api.tenant.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.domain.CompanyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A company, the tenant boundary (SPEC-TEN). Never serialized to an API response: controllers use DTOs. */
@Entity
@Table(name = "company")
public class Company {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CompanyStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Set when an operator disables the Owner's account (D-54); null when the company is not flagged. */
	@Column(name = "ownership_resolution_at")
	private Instant ownershipResolutionAt;

	@Version
	private long version;

	protected Company() {
	}

	/** A new ACTIVE company. The caller must add the Owner membership in the same transaction. */
	public static Company create(String name, Instant now) {
		Company company = new Company();
		company.id = UUID.randomUUID();
		company.name = name;
		company.status = CompanyStatus.ACTIVE;
		company.createdAt = now;
		company.updatedAt = now;
		return company;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public CompanyStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getOwnershipResolutionAt() {
		return ownershipResolutionAt;
	}

}
