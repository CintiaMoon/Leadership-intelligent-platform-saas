package com.example.saas.api.tenant.testrecords;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tenant-owned record that references a {@link TestRecord} (test source tree only). */
@Entity
@Table(name = "test_note")
public class TestNote {

	@Id
	private UUID id;

	@Column(name = "company_id", nullable = false, updatable = false)
	private UUID companyId;

	@Column(name = "record_id", nullable = false, updatable = false)
	private UUID recordId;

	@Column(nullable = false)
	private String body;

	protected TestNote() {
	}

	public TestNote(UUID companyId, UUID recordId, String body) {
		this.id = UUID.randomUUID();
		this.companyId = companyId;
		this.recordId = recordId;
		this.body = body;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public UUID getRecordId() {
		return recordId;
	}

}
