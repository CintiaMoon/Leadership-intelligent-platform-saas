package com.example.saas.api.tenant.testrecords;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tenant-owned stand-in for a core business record (test source tree only). */
@Entity
@Table(name = "test_record")
public class TestRecord {

	@Id
	private UUID id;

	@Column(name = "company_id", nullable = false, updatable = false)
	private UUID companyId;

	@Column(nullable = false)
	private String name;

	protected TestRecord() {
	}

	public TestRecord(UUID companyId, String name) {
		this.id = UUID.randomUUID();
		this.companyId = companyId;
		this.name = name;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public String getName() {
		return name;
	}

}
