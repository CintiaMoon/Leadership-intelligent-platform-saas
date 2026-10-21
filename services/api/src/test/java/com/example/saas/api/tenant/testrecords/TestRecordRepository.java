package com.example.saas.api.tenant.testrecords;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Scoped like every tenant-owned repository: the company is part of every lookup (D-110). */
public interface TestRecordRepository extends Repository<TestRecord, UUID> {

	TestRecord save(TestRecord record);

	Optional<TestRecord> findByCompanyIdAndId(UUID companyId, UUID id);

}
