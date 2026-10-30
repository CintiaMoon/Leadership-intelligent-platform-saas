package com.example.saas.api.tenant.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.example.saas.api.plan.domain.SubscriptionState;

import static com.example.saas.api.tenant.domain.Grant.ALLOWED;
import static com.example.saas.api.tenant.domain.Grant.DENIED;
import static com.example.saas.api.tenant.domain.Grant.SYSTEM_ONLY;
import static com.example.saas.api.tenant.domain.Grant.TARGET_AGENT_OR_VIEWER;
import static com.example.saas.api.tenant.domain.Grant.VIEW_ONLY;

/**
 * The role-permission matrix of SPEC-TEN as a single table (D-109). This is the only place that decides what a
 * role may do; a test compares it with the spec. Pure and stateless: loading the membership and company is the
 * authorization service's job.
 *
 * <p>
 * The matrix has a second dimension, the effective subscription state (SPEC-PLAN, plan Q-08 and section 3a). The state
 * only takes away: a cell the matrix denies stays denied, and a state never grants what the role does not hold.
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
		// Only the initiator may cancel: OwnershipTransferTransactions.cancel checks that the caller started the transfer.
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

	/** How a permission behaves when the subscription is not running normally (plan section 3a). */
	private enum StateAccess {

		/** Open in every state, as the matrix says (deletion requests, the plan row of the Owner). */
		ALWAYS,

		/** Reads: as the matrix says unless the plan has ended, then the Owner only. */
		READS,

		/** Open while trialing, active or read-only; refused once the plan has ended (EXPIRED_LOCKED). */
		UNTIL_LOCKED,

		/** Open only while trialing or active; refused in the read-only, no-plan and locked states. */
		RUNNING_ONLY

	}

	private static final Map<Permission, StateAccess> STATE_ACCESS = new EnumMap<>(Permission.class);

	private static final Set<SubscriptionState> RUNNING = EnumSet.of(SubscriptionState.TRIALING,
			SubscriptionState.ACTIVE);

	static {
		stateAccess(StateAccess.READS, Permission.VIEW_COMPANY_DETAILS, Permission.VIEW_CORE_RECORDS,
				Permission.VIEW_REPORTS, Permission.VIEW_MEMBER_LIST, Permission.VIEW_PLAN);
		stateAccess(StateAccess.RUNNING_ONLY, Permission.CREATE_CORE_RECORDS, Permission.EDIT_CORE_RECORDS,
				Permission.DELETE_CORE_RECORDS, Permission.MANAGE_SETTINGS, Permission.EXPORT_DATA,
				Permission.INVITE_MEMBERS, Permission.REVOKE_OR_RESEND_INVITATIONS, Permission.CHANGE_MEMBER_ROLE,
				Permission.ASSIGN_OR_REVOKE_ADMIN, Permission.REACTIVATE_MEMBERS);
		stateAccess(StateAccess.UNTIL_LOCKED, Permission.SUSPEND_OR_REMOVE_MEMBERS, Permission.LEAVE_COMPANY,
				Permission.TRANSFER_OWNERSHIP, Permission.CANCEL_OWNERSHIP_TRANSFER,
				Permission.ACCEPT_OWNERSHIP_TRANSFER);
		stateAccess(StateAccess.ALWAYS, Permission.MANAGE_PLANS, Permission.REQUEST_COMPANY_DELETION,
				Permission.CANCEL_COMPANY_DELETION, Permission.PURGE_COMPANY_DATA);
		for (Permission permission : Permission.values()) {
			if (!STATE_ACCESS.containsKey(permission)) {
				// A permission added without a decision for the plan states must not silently stay open.
				throw new IllegalStateException("No subscription-state rule for " + permission);
			}
		}
	}

	private CompanyPolicy() {
	}

	private static void stateAccess(StateAccess access, Permission... permissions) {
		for (Permission permission : permissions) {
			STATE_ACCESS.put(permission, access);
		}
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
		if (companyStatus != CompanyStatus.ACTIVE && !allowedWhilePendingDeletion(role, permission)) {
			return false;
		}
		return switch (grant(role, permission)) {
			case ALLOWED -> true;
			case TARGET_AGENT_OR_VIEWER -> targetRole == Role.AGENT || targetRole == Role.VIEWER;
			case DENIED, VIEW_ONLY, SYSTEM_ONLY -> false;
		};
	}

	/** Why a request was refused, so the API can answer 403 {@code FORBIDDEN} or 403 {@code PLAN_RESTRICTED}. */
	public enum Refusal {

		NONE, ROLE, PLAN_STATE

	}

	/**
	 * The full decision: role, company status and subscription state. The role and company status are decided first, so
	 * a caller whose role never holds the permission is told {@link Refusal#ROLE} whatever the state.
	 */
	public static Refusal check(Role role, Permission permission, CompanyStatus companyStatus, SubscriptionState state,
			Role targetRole) {
		if (!permits(role, permission, companyStatus, targetRole)) {
			return Refusal.ROLE;
		}
		return stateAllows(permission, role, state) ? Refusal.NONE : Refusal.PLAN_STATE;
	}

	/** {@link #check} as a yes or no. */
	public static boolean permits(Role role, Permission permission, CompanyStatus companyStatus,
			SubscriptionState state, Role targetRole) {
		return check(role, permission, companyStatus, state, targetRole) == Refusal.NONE;
	}

	/**
	 * Whether the subscription state alone lets this role do this (the second dimension; the matrix is separate). A
	 * missing state is refused.
	 */
	public static boolean stateAllows(Permission permission, Role role, SubscriptionState state) {
		if (state == null) {
			return false;
		}
		return switch (STATE_ACCESS.get(permission)) {
			case ALWAYS -> true;
			case READS -> state != SubscriptionState.EXPIRED_LOCKED || role == Role.OWNER;
			case UNTIL_LOCKED -> state != SubscriptionState.EXPIRED_LOCKED;
			case RUNNING_ONLY -> RUNNING.contains(state);
		};
	}

	/** Revoking an invitation only withdraws it, so it is allowed until the plan has ended (plan section 3a). */
	public static boolean stateAllowsRevokingInvitation(SubscriptionState state) {
		return state != null && state != SubscriptionState.EXPIRED_LOCKED;
	}

	/** Accepting an invitation adds a member, so it needs a running subscription (plan section 3a). */
	public static boolean stateAllowsInvitationAcceptance(SubscriptionState state) {
		return state != null && RUNNING.contains(state);
	}

	/**
	 * While a company is anything but ACTIVE only the Owner may act, and only to read or to cancel the deletion
	 * (SPEC-TEN "Pending deletion"; AC-DEL-12, AC-DEL-14). Whether a cancellation is still possible in a purge state is
	 * decided by the deletion service under the lock (state and deadline); phase 3 narrows the reads of the later states.
	 */
	private static boolean allowedWhilePendingDeletion(Role role, Permission permission) {
		return role == Role.OWNER && (!permission.stateChanging() || permission == Permission.CANCEL_COMPANY_DELETION);
	}

}
