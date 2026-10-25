package com.example.saas.api.tenant.domain;

import java.util.EnumMap;
import java.util.Map;

import static com.example.saas.api.tenant.domain.Grant.ALLOWED;
import static com.example.saas.api.tenant.domain.Grant.DENIED;
import static com.example.saas.api.tenant.domain.Grant.SYSTEM_ONLY;
import static com.example.saas.api.tenant.domain.Grant.TARGET_AGENT_OR_VIEWER;
import static com.example.saas.api.tenant.domain.Grant.VIEW_ONLY;

/**
 * The role-permission matrix of SPEC-TEN as a single table (D-109). This is the only place that decides what a
 * role may do; a test compares it with the spec. Pure and stateless: loading the membership and company is the
 * authorization service's job.
 */
public final class CompanyPolicy {

	private static final Map<Permission, Map<Role, Grant>> MATRIX = new EnumMap<>(Permission.class);

	static {
		row(Permission.VIEW_COMPANY_DETAILS, ALLOWED, ALLOWED, ALLOWED, ALLOWED);
		row(Permission.VIEW_CORE_RECORDS, ALLOWED, ALLOWED, ALLOWED, ALLOWED);
		row(Permission.CREATE_CORE_RECORDS, ALLOWED, ALLOWED, ALLOWED, DENIED);
		row(Permission.EDIT_CORE_RECORDS, ALLOWED, ALLOWED, ALLOWED, DENIED);
		row(Permission.DELETE_CORE_RECORDS, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.VIEW_REPORTS, ALLOWED, ALLOWED, ALLOWED, ALLOWED);
		row(Permission.EXPORT_DATA, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.INVITE_MEMBERS, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.REVOKE_OR_RESEND_INVITATIONS, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.VIEW_MEMBER_LIST, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.CHANGE_MEMBER_ROLE, ALLOWED, TARGET_AGENT_OR_VIEWER, DENIED, DENIED);
		row(Permission.SUSPEND_OR_REMOVE_MEMBERS, ALLOWED, TARGET_AGENT_OR_VIEWER, DENIED, DENIED);
		row(Permission.REACTIVATE_MEMBERS, ALLOWED, TARGET_AGENT_OR_VIEWER, DENIED, DENIED);
		row(Permission.LEAVE_COMPANY, DENIED, ALLOWED, ALLOWED, ALLOWED);
		row(Permission.ASSIGN_OR_REVOKE_ADMIN, ALLOWED, DENIED, DENIED, DENIED);
		row(Permission.TRANSFER_OWNERSHIP, ALLOWED, DENIED, DENIED, DENIED);
		// Only the initiator may cancel; that check belongs to slice 005.
		row(Permission.CANCEL_OWNERSHIP_TRANSFER, ALLOWED, DENIED, DENIED, DENIED);
		// Identity-based like Leave: every role has the row, the service allows only the named recipient (D-181).
		row(Permission.ACCEPT_OWNERSHIP_TRANSFER, ALLOWED, ALLOWED, ALLOWED, ALLOWED);
		row(Permission.MANAGE_SETTINGS, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.MANAGE_PLANS, ALLOWED, VIEW_ONLY, DENIED, DENIED);
		row(Permission.VIEW_PLAN, ALLOWED, ALLOWED, DENIED, DENIED);
		row(Permission.REQUEST_COMPANY_DELETION, ALLOWED, DENIED, DENIED, DENIED);
		row(Permission.CANCEL_COMPANY_DELETION, ALLOWED, DENIED, DENIED, DENIED);
		row(Permission.PURGE_COMPANY_DATA, SYSTEM_ONLY, SYSTEM_ONLY, SYSTEM_ONLY, SYSTEM_ONLY);
	}

	private CompanyPolicy() {
	}

	private static void row(Permission permission, Grant owner, Grant admin, Grant agent, Grant viewer) {
		Map<Role, Grant> cells = new EnumMap<>(Role.class);
		cells.put(Role.OWNER, owner);
		cells.put(Role.ADMIN, admin);
		cells.put(Role.AGENT, agent);
		cells.put(Role.VIEWER, viewer);
		MATRIX.put(permission, cells);
	}

	/** The matrix cell for a role. */
	public static Grant grant(Role role, Permission permission) {
		return MATRIX.get(permission).get(role);
	}

	/**
	 * Whether an ACTIVE member with this role may do this, in a company in this state.
	 *
	 * @param targetRole the role of the membership being acted on, or {@code null} when the permission has no target.
	 * A conditional cell with no target is denied.
	 */
	public static boolean permits(Role role, Permission permission, CompanyStatus companyStatus, Role targetRole) {
		if (companyStatus == CompanyStatus.PENDING_DELETION && !allowedWhilePendingDeletion(role, permission)) {
			return false;
		}
		return switch (grant(role, permission)) {
			case ALLOWED -> true;
			case TARGET_AGENT_OR_VIEWER -> targetRole == Role.AGENT || targetRole == Role.VIEWER;
			case DENIED, VIEW_ONLY, SYSTEM_ONLY -> false;
		};
	}

	/**
	 * While a company is PENDING_DELETION only the Owner may act, and only to read or to cancel the deletion
	 * (SPEC-TEN "Pending deletion"; AC-DEL-12, AC-DEL-14).
	 */
	private static boolean allowedWhilePendingDeletion(Role role, Permission permission) {
		return role == Role.OWNER && (!permission.stateChanging() || permission == Permission.CANCEL_COMPANY_DELETION);
	}

}
