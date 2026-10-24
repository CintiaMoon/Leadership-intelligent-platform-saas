package com.example.saas.api.invitation.domain;

import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.Grant;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;

/**
 * Who may invite whom (SPEC-INV, D-27, D-148), as one pure function over values the caller has loaded under the locks.
 * Permission comes from {@link CompanyPolicy}, so the matrix stays in one place; this class adds the role-grant table: an
 * Owner may invite Admin, Agent or Viewer, an Admin only Agent or Viewer, nobody an Owner. Resending and revoking follow
 * the same table (an actor handles only invitations for roles that actor may invite). Checks run permission first, then
 * the request itself, so a caller who may not invite is refused (and audited) rather than told what is valid.
 */
public final class InvitationRules {

	public enum Operation {

		CREATE, RESEND, REVOKE

	}

	public enum Outcome {

		ALLOWED, FORBIDDEN, INVALID

	}

	/**
	 * @param deniedPermission for FORBIDDEN, the matrix row that refused it (audited); otherwise null
	 * @param recentAuthenticationRequired for ALLOWED, whether the session must have logged in or stepped up within the
	 *        window: creating or resending an invitation for Admin (AC-INV-27)
	 */
	public record Decision(Outcome outcome, Permission deniedPermission, boolean recentAuthenticationRequired) {

		static Decision allowed(boolean recentAuthentication) {
			return new Decision(Outcome.ALLOWED, null, recentAuthentication);
		}

		static Decision forbidden(Permission permission) {
			return new Decision(Outcome.FORBIDDEN, permission, false);
		}

		static Decision invalid() {
			return new Decision(Outcome.INVALID, null, false);
		}

	}

	private InvitationRules() {
	}

	private static Permission permissionFor(Operation operation) {
		return operation == Operation.CREATE ? Permission.INVITE_MEMBERS : Permission.REVOKE_OR_RESEND_INVITATIONS;
	}

	/**
	 * The matrix row that refuses this role whatever the invited role is, or null if the role might be allowed. Agents and
	 * Viewers never manage invitations, so they are refused before any lock is taken and before anything is counted
	 * (D-137, D-148): a member with no such permission must not be able to queue for the company lock or use up its
	 * invitation allowance.
	 */
	public static Permission refusedWhateverTheRole(Operation operation, Role actorRole) {
		Permission needed = permissionFor(operation);
		Grant grant = CompanyPolicy.grant(actorRole, needed);
		return grant == Grant.DENIED || grant == Grant.SYSTEM_ONLY ? needed : null;
	}

	/** Whether {@code actor} may hand out the role. Owner is never invitable. */
	public static boolean mayGrant(Role actor, Role invited) {
		if (invited == null || invited == Role.OWNER) {
			return false;
		}
		return switch (actor) {
			case OWNER -> true;
			case ADMIN -> invited == Role.AGENT || invited == Role.VIEWER;
			case AGENT, VIEWER -> false;
		};
	}

	/**
	 * @param invitedRole the role being invited (CREATE) or the role of the invitation being resent or revoked
	 * @param companyStatus the company's lifecycle state; every invitation operation is refused while it is pending
	 *        deletion (AC-INV-13)
	 */
	public static Decision decide(Operation operation, Role actorRole, Role invitedRole, CompanyStatus companyStatus) {
		Permission needed = permissionFor(operation);
		if (!CompanyPolicy.permits(actorRole, needed, companyStatus, null)) {
			return Decision.forbidden(needed);
		}
		// Nobody is invited as Owner (D-27). A missing role is a malformed request, not a permission question.
		if (invitedRole == null || invitedRole == Role.OWNER) {
			return Decision.invalid();
		}
		if (!mayGrant(actorRole, invitedRole)) {
			return Decision.forbidden(needed);
		}
		return Decision.allowed(invitedRole == Role.ADMIN && operation != Operation.REVOKE);
	}

}
