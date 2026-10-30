package com.example.saas.api.tenant.domain;

import java.util.Set;

import com.example.saas.api.tenant.domain.ChangeDecision.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every combination of operation, actor, target, status, self, requested role and company state, checked against a
 * second statement of the rules written from the SPEC-MEM text (not from {@link CompanyPolicy}), plus literal cases for
 * the acceptance criteria that name them.
 */
class MembershipChangesTest {

	private static final Set<Role> LOWER = Set.of(Role.AGENT, Role.VIEWER);

	private static ChangeDecision decide(MembershipOperation op, Role actor, Role target, MembershipStatus status,
			boolean self, Role newRole) {
		return MembershipChanges.decide(op, actor, target, status, self, newRole, CompanyStatus.ACTIVE);
	}

	/** The rules as the spec states them, one question at a time. */
	private static Outcome expected(MembershipOperation op, Role actor, Role target, MembershipStatus status,
			boolean self, Role newRole, CompanyStatus company) {
		// While a company is anything but ACTIVE (pending deletion, being purged) nothing here is allowed to anyone (AC-DEL-12).
		if (company != CompanyStatus.ACTIVE) {
			return Outcome.FORBIDDEN;
		}
		boolean touchesAdminRole = op == MembershipOperation.CHANGE_ROLE
				&& (newRole == Role.ADMIN || target == Role.ADMIN);
		boolean permitted;
		if (op == MembershipOperation.LEAVE) {
			permitted = actor != Role.OWNER;
		}
		else if (touchesAdminRole) {
			permitted = actor == Role.OWNER;
		}
		else {
			permitted = actor == Role.OWNER || (actor == Role.ADMIN && LOWER.contains(target));
		}
		if (!permitted) {
			return Outcome.FORBIDDEN;
		}
		// The request itself is judged only after the caller is known to be allowed to ask.
		if (op == MembershipOperation.CHANGE_ROLE && (newRole == null || newRole == Role.OWNER)) {
			return Outcome.INVALID;
		}
		if (op != MembershipOperation.LEAVE && target == Role.OWNER) {
			return Outcome.CONFLICT;
		}
		if (op == MembershipOperation.CHANGE_ROLE && self) {
			return Outcome.FORBIDDEN;
		}
		boolean possible = switch (op) {
			case CHANGE_ROLE -> status == MembershipStatus.ACTIVE && newRole != target;
			case SUSPEND, LEAVE -> status == MembershipStatus.ACTIVE;
			case REACTIVATE -> status == MembershipStatus.SUSPENDED;
			case REMOVE -> status != MembershipStatus.REMOVED;
		};
		return possible ? Outcome.ALLOWED : Outcome.CONFLICT;
	}

	@Test
	void everyCombinationMatchesTheSpecStatement() {
		Role[] requested = { null, Role.OWNER, Role.ADMIN, Role.AGENT, Role.VIEWER };
		int checked = 0;
		for (CompanyStatus company : CompanyStatus.values()) {
			for (MembershipOperation op : MembershipOperation.values()) {
				for (Role actor : Role.values()) {
					for (Role target : Role.values()) {
						for (MembershipStatus status : MembershipStatus.values()) {
							for (boolean self : new boolean[] { false, true }) {
								for (Role newRole : requested) {
									// LEAVE is always about the actor's own membership.
									Role effectiveTarget = op == MembershipOperation.LEAVE ? actor : target;
									boolean effectiveSelf = op == MembershipOperation.LEAVE || self;
									ChangeDecision actual = MembershipChanges.decide(op, actor, effectiveTarget, status,
											effectiveSelf, newRole, company);
									Outcome want = expected(op, actor, effectiveTarget, status, effectiveSelf, newRole,
											company);
									assertThat(actual.outcome())
										.as("%s by %s on %s/%s self=%s newRole=%s company=%s", op, actor, effectiveTarget,
												status, effectiveSelf, newRole, company)
										.isEqualTo(want);
									assertThat(actual.recentAuthenticationRequired())
										.as("recent authentication for %s %s %s", op, actor, newRole)
										.isEqualTo(want == Outcome.ALLOWED && op == MembershipOperation.CHANGE_ROLE
												&& (newRole == Role.ADMIN || effectiveTarget == Role.ADMIN));
									assertThat(actual.deniedPermission() != null).isEqualTo(want == Outcome.FORBIDDEN);
									checked++;
								}
							}
						}
					}
				}
			}
		}
		assertThat(checked).isEqualTo(CompanyStatus.values().length * 5 * 4 * 4 * 3 * 2 * 5);
	}

	@Test
	void ac10TheRoleAssignmentTable() {
		// Owner may assign Admin, Agent or Viewer; Admin may assign Agent or Viewer; Agent and Viewer none; nobody Owner.
		for (Role newRole : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
			Role current = newRole == Role.AGENT ? Role.VIEWER : Role.AGENT;
			assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, current, MembershipStatus.ACTIVE, false, newRole)
				.outcome()).as("Owner assigns %s", newRole).isEqualTo(Outcome.ALLOWED);
		}
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.ADMIN, Role.VIEWER, MembershipStatus.ACTIVE, false,
				Role.AGENT).outcome()).isEqualTo(Outcome.ALLOWED);
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.ADMIN, Role.AGENT, MembershipStatus.ACTIVE, false,
				Role.VIEWER).outcome()).isEqualTo(Outcome.ALLOWED);
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.ADMIN, Role.AGENT, MembershipStatus.ACTIVE, false,
				Role.ADMIN).outcome()).as("an Admin cannot create Admins").isEqualTo(Outcome.FORBIDDEN);
		for (Role actor : new Role[] { Role.AGENT, Role.VIEWER }) {
			assertThat(decide(MembershipOperation.CHANGE_ROLE, actor, Role.VIEWER, MembershipStatus.ACTIVE, false,
					Role.AGENT).outcome()).isEqualTo(Outcome.FORBIDDEN);
		}
		for (Role actor : Role.values()) {
			// A caller who may change roles asked for something nobody may do; one who may not is simply refused.
			Outcome expected = actor == Role.OWNER || actor == Role.ADMIN ? Outcome.INVALID : Outcome.FORBIDDEN;
			assertThat(decide(MembershipOperation.CHANGE_ROLE, actor, Role.AGENT, MembershipStatus.ACTIVE, false,
					Role.OWNER).outcome()).as("%s assigning Owner", actor).isEqualTo(expected);
		}
	}

	@Test
	void ac11OnlyAnOwnerPromotesDemotesOrRemovesAnAdmin() {
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, Role.AGENT, MembershipStatus.ACTIVE, false,
				Role.ADMIN)).extracting(ChangeDecision::outcome, ChangeDecision::recentAuthenticationRequired)
			.containsExactly(Outcome.ALLOWED, true);
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, Role.ADMIN, MembershipStatus.ACTIVE, false,
				Role.VIEWER)).extracting(ChangeDecision::outcome, ChangeDecision::recentAuthenticationRequired)
			.containsExactly(Outcome.ALLOWED, true);
		assertThat(decide(MembershipOperation.REMOVE, Role.OWNER, Role.ADMIN, MembershipStatus.ACTIVE, false, null))
			.extracting(ChangeDecision::outcome, ChangeDecision::recentAuthenticationRequired)
			.as("removing an Admin is not on the recent-authentication list")
			.containsExactly(Outcome.ALLOWED, false);
		for (MembershipOperation op : new MembershipOperation[] { MembershipOperation.REMOVE,
				MembershipOperation.SUSPEND }) {
			assertThat(decide(op, Role.ADMIN, Role.ADMIN, MembershipStatus.ACTIVE, false, null).outcome())
				.as("an Admin %s an Admin", op)
				.isEqualTo(Outcome.FORBIDDEN);
		}
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.ADMIN, Role.ADMIN, MembershipStatus.ACTIVE, false,
				Role.AGENT).outcome()).isEqualTo(Outcome.FORBIDDEN);
	}

	@Test
	void ac12NobodyChangesTheirOwnRole() {
		for (Role actor : new Role[] { Role.OWNER, Role.ADMIN, Role.AGENT, Role.VIEWER }) {
			for (Role newRole : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
				Outcome outcome = decide(MembershipOperation.CHANGE_ROLE, actor, actor, MembershipStatus.ACTIVE, true,
						newRole).outcome();
				assertThat(outcome).as("%s to %s on themselves", actor, newRole).isNotEqualTo(Outcome.ALLOWED);
			}
		}
	}

	@Test
	void ac15AnAdminCannotTouchAnOwnerOrAnotherAdminButCanActOnAgentsAndViewers() {
		for (MembershipOperation op : new MembershipOperation[] { MembershipOperation.SUSPEND,
				MembershipOperation.REMOVE }) {
			for (Role target : new Role[] { Role.OWNER, Role.ADMIN }) {
				assertThat(decide(op, Role.ADMIN, target, MembershipStatus.ACTIVE, false, null).outcome())
					.as("Admin %s %s", op, target)
					.isEqualTo(Outcome.FORBIDDEN);
			}
			for (Role target : LOWER) {
				assertThat(decide(op, Role.ADMIN, target, MembershipStatus.ACTIVE, false, null).outcome())
					.as("Admin %s %s", op, target)
					.isEqualTo(Outcome.ALLOWED);
			}
		}
		for (Role target : new Role[] { Role.OWNER, Role.ADMIN }) {
			assertThat(decide(MembershipOperation.REACTIVATE, Role.ADMIN, target, MembershipStatus.SUSPENDED, false, null)
				.outcome()).isEqualTo(Outcome.FORBIDDEN);
			assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.ADMIN, target, MembershipStatus.ACTIVE, false,
					Role.AGENT).outcome()).isEqualTo(Outcome.FORBIDDEN);
		}
		for (Role target : LOWER) {
			assertThat(decide(MembershipOperation.REACTIVATE, Role.ADMIN, target, MembershipStatus.SUSPENDED, false, null)
				.outcome()).isEqualTo(Outcome.ALLOWED);
		}
	}

	@Test
	void ac03AndAc09TheOwnerIsNeverChangedAndCannotLeave() {
		for (MembershipOperation op : new MembershipOperation[] { MembershipOperation.SUSPEND,
				MembershipOperation.REMOVE, MembershipOperation.REACTIVATE }) {
			assertThat(decide(op, Role.OWNER, Role.OWNER, MembershipStatus.ACTIVE, true, null).outcome())
				.as("Owner %s Owner", op)
				.isEqualTo(Outcome.CONFLICT);
		}
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, Role.OWNER, MembershipStatus.ACTIVE, true,
				Role.ADMIN).outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(decide(MembershipOperation.LEAVE, Role.OWNER, Role.OWNER, MembershipStatus.ACTIVE, true, null)
			.outcome()).isEqualTo(Outcome.FORBIDDEN);
		for (Role member : LOWER) {
			assertThat(decide(MembershipOperation.LEAVE, member, member, MembershipStatus.ACTIVE, true, null).outcome())
				.isEqualTo(Outcome.ALLOWED);
		}
		assertThat(decide(MembershipOperation.LEAVE, Role.ADMIN, Role.ADMIN, MembershipStatus.ACTIVE, true, null)
			.outcome()).isEqualTo(Outcome.ALLOWED);
	}

	@Test
	void theTransitionsAreExactlyTheOnesInThePlan() {
		assertThat(decide(MembershipOperation.SUSPEND, Role.OWNER, Role.AGENT, MembershipStatus.SUSPENDED, false, null)
			.outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(decide(MembershipOperation.REACTIVATE, Role.OWNER, Role.AGENT, MembershipStatus.ACTIVE, false, null)
			.outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(decide(MembershipOperation.REMOVE, Role.OWNER, Role.AGENT, MembershipStatus.SUSPENDED, false, null)
			.outcome()).isEqualTo(Outcome.ALLOWED);
		assertThat(decide(MembershipOperation.REMOVE, Role.OWNER, Role.AGENT, MembershipStatus.REMOVED, false, null)
			.outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, Role.AGENT, MembershipStatus.SUSPENDED, false,
				Role.VIEWER).outcome()).as("a suspended member is reactivated first").isEqualTo(Outcome.CONFLICT);
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, Role.AGENT, MembershipStatus.ACTIVE, false,
				Role.AGENT).outcome()).as("no change of role is a conflict").isEqualTo(Outcome.CONFLICT);
	}

	@Test
	void aDeniedDecisionNamesTheRowThatRefusedIt() {
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.ADMIN, Role.AGENT, MembershipStatus.ACTIVE, false,
				Role.ADMIN).deniedPermission()).isEqualTo(Permission.ASSIGN_OR_REVOKE_ADMIN);
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.AGENT, Role.VIEWER, MembershipStatus.ACTIVE, false,
				Role.AGENT).deniedPermission()).isEqualTo(Permission.CHANGE_MEMBER_ROLE);
		assertThat(decide(MembershipOperation.SUSPEND, Role.VIEWER, Role.AGENT, MembershipStatus.ACTIVE, false, null)
			.deniedPermission()).isEqualTo(Permission.SUSPEND_OR_REMOVE_MEMBERS);
		assertThat(decide(MembershipOperation.REACTIVATE, Role.AGENT, Role.AGENT, MembershipStatus.SUSPENDED, false,
				null).deniedPermission()).isEqualTo(Permission.REACTIVATE_MEMBERS);
		assertThat(decide(MembershipOperation.LEAVE, Role.OWNER, Role.OWNER, MembershipStatus.ACTIVE, true, null)
			.deniedPermission()).isEqualTo(Permission.LEAVE_COMPANY);
	}

	@Test
	void aCallerWhoMayNotChangeRolesIsRefusedEvenWhenTheRequestIsMalformed() {
		for (Role actor : new Role[] { Role.AGENT, Role.VIEWER }) {
			assertThat(decide(MembershipOperation.CHANGE_ROLE, actor, Role.VIEWER, MembershipStatus.ACTIVE, false, Role.OWNER)
				.outcome()).as("%s sending OWNER", actor).isEqualTo(Outcome.FORBIDDEN);
			assertThat(decide(MembershipOperation.CHANGE_ROLE, actor, Role.VIEWER, MembershipStatus.ACTIVE, false, null)
				.outcome()).as("%s sending nothing", actor).isEqualTo(Outcome.FORBIDDEN);
		}
		assertThat(decide(MembershipOperation.CHANGE_ROLE, Role.OWNER, Role.AGENT, MembershipStatus.ACTIVE, false,
				Role.OWNER).outcome()).as("an Owner sending OWNER is a bad request").isEqualTo(Outcome.INVALID);
	}

	@Test
	void refusedWhateverTheTargetNamesTheRowOnlyForRolesThatCanNeverDoIt() {
		for (MembershipOperation op : new MembershipOperation[] { MembershipOperation.CHANGE_ROLE,
				MembershipOperation.SUSPEND, MembershipOperation.REACTIVATE, MembershipOperation.REMOVE }) {
			assertThat(MembershipChanges.refusedWhateverTheTarget(op, Role.OWNER)).as("Owner %s", op).isNull();
			assertThat(MembershipChanges.refusedWhateverTheTarget(op, Role.ADMIN)).as("Admin %s", op).isNull();
			assertThat(MembershipChanges.refusedWhateverTheTarget(op, Role.AGENT)).as("Agent %s", op).isNotNull();
			assertThat(MembershipChanges.refusedWhateverTheTarget(op, Role.VIEWER)).as("Viewer %s", op).isNotNull();
		}
		assertThat(MembershipChanges.refusedWhateverTheTarget(MembershipOperation.SUSPEND, Role.AGENT))
			.isEqualTo(Permission.SUSPEND_OR_REMOVE_MEMBERS);
		assertThat(MembershipChanges.refusedWhateverTheTarget(MembershipOperation.LEAVE, Role.OWNER))
			.isEqualTo(Permission.LEAVE_COMPANY);
		for (Role role : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
			assertThat(MembershipChanges.refusedWhateverTheTarget(MembershipOperation.LEAVE, role)).isNull();
		}
	}

}
