package com.example.saas.api.tenant;

import java.util.Map;
import java.util.UUID;

import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.testrecords.TestNote;
import com.example.saas.api.tenant.testrecords.TestNoteRepository;
import com.example.saas.api.tenant.testrecords.TestRecord;
import com.example.saas.api.tenant.testrecords.TestRecordRepository;
import com.example.saas.api.tenant.web.Authorized;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only routes (D-113): one per matrix permission, so every Permission x Role cell is exercised over HTTP, plus two
 * routes that touch tenant-owned records. Real endpoints replace them as slices 003 to 007 land. Spring Security's
 * production chain denies these paths; only {@link TenantTestEndpointsConfiguration} opens them. A route names its
 * permission only through {@link Authorized}.
 */
@RestController
class PermissionRoutesController {

	private final TestRecordRepository records;

	private final TestNoteRepository notes;

	PermissionRoutesController(TestRecordRepository records, TestNoteRepository notes) {
		this.records = records;
		this.notes = notes;
	}

	@GetMapping("/api/v1/companies/{companyId}/test/view-company-details")
	Map<String, String> viewCompanyDetails(@Authorized(Permission.VIEW_COMPANY_DETAILS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/view-core-records")
	Map<String, String> viewCoreRecords(@Authorized(Permission.VIEW_CORE_RECORDS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/create-core-records")
	Map<String, String> createCoreRecords(@Authorized(Permission.CREATE_CORE_RECORDS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/edit-core-records")
	Map<String, String> editCoreRecords(@Authorized(Permission.EDIT_CORE_RECORDS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/delete-core-records")
	Map<String, String> deleteCoreRecords(@Authorized(Permission.DELETE_CORE_RECORDS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/view-reports")
	Map<String, String> viewReports(@Authorized(Permission.VIEW_REPORTS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/export-data")
	Map<String, String> exportData(@Authorized(Permission.EXPORT_DATA) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/invite-members")
	Map<String, String> inviteMembers(@Authorized(Permission.INVITE_MEMBERS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/revoke-or-resend-invitations")
	Map<String, String> revokeOrResendInvitations(@Authorized(Permission.REVOKE_OR_RESEND_INVITATIONS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/view-member-list")
	Map<String, String> viewMemberList(@Authorized(Permission.VIEW_MEMBER_LIST) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/change-member-role")
	Map<String, String> changeMemberRole(@Authorized(Permission.CHANGE_MEMBER_ROLE) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/suspend-or-remove-members")
	Map<String, String> suspendOrRemoveMembers(@Authorized(Permission.SUSPEND_OR_REMOVE_MEMBERS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/reactivate-members")
	Map<String, String> reactivateMembers(@Authorized(Permission.REACTIVATE_MEMBERS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/leave-company")
	Map<String, String> leaveCompany(@Authorized(Permission.LEAVE_COMPANY) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/assign-or-revoke-admin")
	Map<String, String> assignOrRevokeAdmin(@Authorized(Permission.ASSIGN_OR_REVOKE_ADMIN) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/transfer-ownership")
	Map<String, String> transferOwnership(@Authorized(Permission.TRANSFER_OWNERSHIP) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/cancel-ownership-transfer")
	Map<String, String> cancelOwnershipTransfer(@Authorized(Permission.CANCEL_OWNERSHIP_TRANSFER) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/manage-settings")
	Map<String, String> manageSettings(@Authorized(Permission.MANAGE_SETTINGS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/manage-plans")
	Map<String, String> managePlans(@Authorized(Permission.MANAGE_PLANS) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/view-plan")
	Map<String, String> viewPlan(@Authorized(Permission.VIEW_PLAN) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/request-company-deletion")
	Map<String, String> requestCompanyDeletion(@Authorized(Permission.REQUEST_COMPANY_DELETION) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/cancel-company-deletion")
	Map<String, String> cancelCompanyDeletion(@Authorized(Permission.CANCEL_COMPANY_DELETION) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	@GetMapping("/api/v1/companies/{companyId}/test/purge-company-data")
	Map<String, String> purgeCompanyData(@Authorized(Permission.PURGE_COMPANY_DATA) AuthorizedCompany company) {
		return Map.of("role", company.role().name());
	}

	/** A read by record ID inside the authorized company; another company's record looks like a missing one. */
	@GetMapping("/api/v1/companies/{companyId}/test/records/{recordId}")
	Map<String, String> record(@PathVariable UUID recordId,
			@Authorized(Permission.VIEW_CORE_RECORDS) AuthorizedCompany company) {
		TestRecord record = records.findByCompanyIdAndId(company.companyId(), recordId)
			.orElseThrow(CompanyNotFoundException::new);
		return Map.of("name", record.getName());
	}

	record NoteRequest(UUID recordId, String body) {
	}

	/** A write that references a related record: the record must belong to the authorized company (AC-TEN-08). */
	@PostMapping("/api/v1/companies/{companyId}/test/notes")
	ResponseEntity<Map<String, String>> addNote(@RequestBody NoteRequest request,
			@Authorized(Permission.CREATE_CORE_RECORDS) AuthorizedCompany company) {
		TestRecord record = records.findByCompanyIdAndId(company.companyId(), request.recordId())
			.orElseThrow(CompanyNotFoundException::new);
		TestNote note = notes.save(new TestNote(company.companyId(), record.getId(), request.body()));
		return ResponseEntity.status(201).body(Map.of("id", note.getId().toString()));
	}

}
