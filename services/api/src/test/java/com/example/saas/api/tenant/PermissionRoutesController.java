package com.example.saas.api.tenant;

import java.util.Map;
import java.util.UUID;

import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.RecentAuthentication;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.application.CompanyAuthorizationService;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.MembershipChangeAuthorizer;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.domain.MembershipOperation;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.testrecords.TestNote;
import com.example.saas.api.tenant.testrecords.TestNoteRepository;
import com.example.saas.api.tenant.testrecords.TestRecord;
import com.example.saas.api.tenant.testrecords.TestRecordRepository;
import com.example.saas.api.tenant.web.Authorized;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only routes (D-116): one per matrix permission, so every Permission x Role cell is exercised over HTTP, plus two
 * routes that touch tenant-owned records. Real endpoints replace them as slices 003 to 007 land. Spring Security's
 * production chain denies these paths; only {@link TenantTestEndpointsConfiguration} opens them. A route names its
 * permission only through {@link Authorized}.
 */
@RestController
class PermissionRoutesController {

	private final TestRecordRepository records;

	private final TestNoteRepository notes;

	private final CompanyAuthorizationService authorization;

	private final CompanyLocks locks;

	private final TransactionTemplate tx;

	private final MembershipChangeAuthorizer changes;

	private final RecentAuthentication recentAuthentication;

	PermissionRoutesController(TestRecordRepository records, TestNoteRepository notes,
			CompanyAuthorizationService authorization, CompanyLocks locks, TransactionTemplate tx,
			MembershipChangeAuthorizer changes, RecentAuthentication recentAuthentication) {
		this.records = records;
		this.notes = notes;
		this.authorization = authorization;
		this.locks = locks;
		this.tx = tx;
		this.changes = changes;
		this.recentAuthentication = recentAuthentication;
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

	@GetMapping("/api/v1/companies/{companyId}/test/accept-ownership-transfer")
	Map<String, String> acceptOwnershipTransfer(@Authorized(Permission.ACCEPT_OWNERSHIP_TRANSFER) AuthorizedCompany company) {
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

	/**
	 * A permission that depends on the role of the membership being acted on ("Agent/Viewer only"). The service is called
	 * with the target role, as the membership slice will do inside its own transaction.
	 */
	@GetMapping("/api/v1/companies/{companyId}/test/targeted/{permission}")
	Map<String, String> targeted(@PathVariable UUID companyId, @PathVariable Permission permission,
			@RequestParam Role targetRole, @AuthenticationPrincipal AuthenticatedUser principal) {
		AuthorizedCompany company = authorization.require(principal.userId(), companyId, permission, targetRole);
		return Map.of("role", company.role().name());
	}

	/** Takes the company lock in its own transaction, so a held lock shows up as the 503 of D-125. */
	@GetMapping("/api/v1/companies/{companyId}/test/lock-company")
	Map<String, String> lockCompany(@Authorized(Permission.VIEW_COMPANY_DETAILS) AuthorizedCompany company) {
		tx.executeWithoutResult(status -> locks.lockCompany(company.companyId()));
		return Map.of("locked", "true");
	}

	/**
	 * Runs the first step of a membership change the way the Phase 3 service will: authorize under the locks in one
	 * transaction, then require recent authentication when the decision says so. Nothing is written.
	 */
	@GetMapping("/api/v1/companies/{companyId}/test/authorize-change/{operation}")
	Map<String, String> authorizeChange(@PathVariable UUID companyId, @PathVariable MembershipOperation operation,
			@RequestParam(required = false) UUID membershipId, @RequestParam(required = false) Role newRole,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		MembershipChangeAuthorizer.Authorized authorized = tx.execute(status -> {
			MembershipChangeAuthorizer.Authorized result = changes.authorize(principal.userId(), companyId,
					membershipId, operation, newRole);
			if (result.decision().recentAuthenticationRequired()) {
				recentAuthentication.require(principal);
			}
			return result;
		});
		return Map.of("actorRole", authorized.actor().getRole().name(), "targetRole",
				authorized.target().getRole().name());
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
