package com.example.saas.api.tenant.domain;

/**
 * The rules for changing a membership (SPEC-MEM; D-126, D-128), as one pure function over values the caller has loaded
 * under the locks. Permission comes from {@link CompanyPolicy}, so the matrix stays in one place; this class adds the
 * role-assignment table, Owner protection, self-change denial and the allowed status transitions.
 *
 * <p>
 * The checks run in a fixed order so a caller learns nothing it should not: first the permission (403), then the Owner
 * rule and the self rule, then whether the change is possible in the current state (409).
 */
public final class MembershipChanges {

	private MembershipChanges() {
	}

	/**
	 * @param operation what the actor asks for
	 * @param actorRole the actor's role in this company, loaded under the lock
	 * @param targetRole the role of the membership being changed; for LEAVE, the actor's own role
	 * @param targetStatus the status of the membership being changed; for LEAVE, the actor's own status
	 * @param self whether the target is the actor's own membership
	 * @param newRole the requested role for CHANGE_ROLE, otherwise ignored
	 * @param companyStatus the company's lifecycle state
	 */
	public static ChangeDecision decide(MembershipOperation operation, Role actorRole, Role targetRole,
			MembershipStatus targetStatus, boolean self, Role newRole, CompanyStatus companyStatus) {
		boolean involvesAdmin = operation == MembershipOperation.CHANGE_ROLE
				&& (newRole == Role.ADMIN || targetRole == Role.ADMIN);

		Permission refused = firstRefusedPermission(operation, actorRole, targetRole, involvesAdmin, companyStatus);
		if (refused != null) {
			return ChangeDecision.forbidden(refused);
		}
		// No role editing assigns Owner (SPEC-MEM table); ownership moves only by transfer (slice 005). Checked after
		// the permission so a caller who may not change roles is refused (and audited) rather than told what is valid.
		if (operation == MembershipOperation.CHANGE_ROLE && (newRole == null || newRole == Role.OWNER)) {
			return ChangeDecision.invalid();
		}
		// Only an Owner gets past the permission check with an Owner as target, and the Owner is never changed here.
		if (operation != MembershipOperation.LEAVE && targetRole == Role.OWNER) {
			return ChangeDecision.conflict();
		}
		if (operation == MembershipOperation.CHANGE_ROLE && self) {
			return ChangeDecision.forbidden(Permission.CHANGE_MEMBER_ROLE);
		}
		if (!transitionIsPossible(operation, targetRole, targetStatus, newRole)) {
			return ChangeDecision.conflict();
		}
		return ChangeDecision.allowed(involvesAdmin);
	}

	/** The matrix row that refuses this actor, or null if every row the operation needs is granted. */
	private static Permission firstRefusedPermission(MembershipOperation operation, Role actorRole, Role targetRole,
			boolean involvesAdmin, CompanyStatus companyStatus) {
		if (involvesAdmin
				&& !CompanyPolicy.permits(actorRole, Permission.ASSIGN_OR_REVOKE_ADMIN, companyStatus, null)) {
			return Permission.ASSIGN_OR_REVOKE_ADMIN;
		}
		Permission needed = neededPermission(operation);
		Role target = operation == MembershipOperation.LEAVE ? null : targetRole;
		return CompanyPolicy.permits(actorRole, needed, companyStatus, target) ? null : needed;
	}

	private static boolean transitionIsPossible(MembershipOperation operation, Role targetRole,
			MembershipStatus targetStatus, Role newRole) {
		return switch (operation) {
			case CHANGE_ROLE -> targetStatus == MembershipStatus.ACTIVE && newRole != targetRole;
			case SUSPEND, LEAVE -> targetStatus == MembershipStatus.ACTIVE;
			case REACTIVATE -> targetStatus == MembershipStatus.SUSPENDED;
			case REMOVE -> targetStatus == MembershipStatus.ACTIVE || targetStatus == MembershipStatus.SUSPENDED;
		};
	}

	private static Permission neededPermission(MembershipOperation operation) {
		return switch (operation) {
			case CHANGE_ROLE -> Permission.CHANGE_MEMBER_ROLE;
			case SUSPEND, REMOVE -> Permission.SUSPEND_OR_REMOVE_MEMBERS;
			case REACTIVATE -> Permission.REACTIVATE_MEMBERS;
			case LEAVE -> Permission.LEAVE_COMPANY;
		};
	}

	/**
	 * The matrix row that refuses this role whatever the target is, or null if the role might be allowed. Agents and
	 * Viewers never manage memberships and the Owner never leaves, so they are refused before any lock is taken: a
	 * member with no such permission must not be able to queue for the company lock and starve the people who do (D-137).
	 */
	public static Permission refusedWhateverTheTarget(MembershipOperation operation, Role actorRole) {
		Permission needed = neededPermission(operation);
		Grant grant = CompanyPolicy.grant(actorRole, needed);
		return grant == Grant.DENIED || grant == Grant.SYSTEM_ONLY ? needed : null;
	}

}
