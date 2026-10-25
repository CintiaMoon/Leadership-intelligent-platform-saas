package com.example.saas.api.tenant.domain;

/**
 * Every row of the role-permission matrix in SPEC-TEN (D-18). The label is the row's text in the spec so a test can
 * compare the code with the spec cell by cell. Anything not listed here is denied.
 */
public enum Permission {

	VIEW_COMPANY_DETAILS("View company details", false),
	VIEW_CORE_RECORDS("View core business records", false),
	CREATE_CORE_RECORDS("Create core business records", true),
	EDIT_CORE_RECORDS("Edit core business records", true),
	DELETE_CORE_RECORDS("Delete core business records", true),
	VIEW_REPORTS("View reports and dashboards", false),
	EXPORT_DATA("Export data", false),
	INVITE_MEMBERS("Invite new members", true),
	REVOKE_OR_RESEND_INVITATIONS("Revoke or resend invitations", true),
	VIEW_MEMBER_LIST("View member list", false),
	CHANGE_MEMBER_ROLE("Change a member's role", true),
	SUSPEND_OR_REMOVE_MEMBERS("Suspend or remove members", true),
	REACTIVATE_MEMBERS("Reactivate suspended members", true),
	LEAVE_COMPANY("Leave the company", true),
	ASSIGN_OR_REVOKE_ADMIN("Assign or revoke Admin role", true),
	TRANSFER_OWNERSHIP("Transfer company ownership", true),
	CANCEL_OWNERSHIP_TRANSFER("Cancel ownership transfer", true),
	ACCEPT_OWNERSHIP_TRANSFER("Accept ownership transfer", true),
	MANAGE_SETTINGS("Manage company settings", true),
	MANAGE_PLANS("Manage plans and entitlements", true),
	VIEW_PLAN("View plan and entitlements", false),
	REQUEST_COMPANY_DELETION("Request company deletion", true),
	CANCEL_COMPANY_DELETION("Cancel pending company deletion", true),
	PURGE_COMPANY_DATA("Permanently delete company data", true);

	private final String label;

	private final boolean stateChanging;

	Permission(String label, boolean stateChanging) {
		this.label = label;
		this.stateChanging = stateChanging;
	}

	/** The matrix row text in the spec. */
	public String label() {
		return label;
	}

	/** Whether the operation changes data. While a company is PENDING_DELETION these are denied (AC-DEL-12). */
	public boolean stateChanging() {
		return stateChanging;
	}

	/** The lower-case code used as the audit reason for a denial. */
	public String code() {
		return name().toLowerCase();
	}

}
