package com.example.saas.api.tenant.testrecords;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface TestNoteRepository extends Repository<TestNote, UUID> {

	TestNote save(TestNote note);

	List<TestNote> findByCompanyId(UUID companyId);

}
