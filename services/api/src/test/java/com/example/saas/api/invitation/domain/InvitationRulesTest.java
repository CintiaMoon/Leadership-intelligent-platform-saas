package com.example.saas.api.invitation.domain;

import java.util.Set;

import com.example.saas.api.invitation.domain.InvitationRules.Decision;
import com.example.saas.api.invitation.domain.InvitationRules.Operation;
import com.example.saas.api.invitation.domain.InvitationRules.Outcome;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every combination of operation, actor, invited role and company state against a second statement of the rules written
 * from SPEC-INV (D-27, AC-INV-17, AC-INV-27), plus literal cases for the table itself.
 */
class InvitationRulesTest {

	private static final Set<Role> LOWER = Set.of(Role.AGENT, Role.VIEWER);

	private static Outcome expected(Operation op, Role actor, Role invited, CompanyStatus company) {
		// While a company is pending deletion no invitation operation is allowed to anyone (SPEC-TEN).
		if (company == CompanyStatus.PENDING_DELETION) {
			return Outcome.FORBIDDEN;
		}
		// Only an Owner or an Admin handles invitations at all.
		if (actor != Role.OWNER && actor != Role.ADMIN) {
			return Outcome.FORBIDDEN;
		}
		// The request itself is judged after the caller is known to be allowed to ask.
		if (invited == null || invited == Role.OWNER) {
			return Outcome.INVALID;
		}
		boolean grantable = actor == Role.OWNER || LOWER.contains(invited);
		return grantable ? Outcome.ALLOWED : Outcome.FORBIDDEN;
	}

	@Test
	void everyCombinationMatchesTheSpecStatement() {
		Role[] invitedRoles = { null, Role.OWNER, Role.ADMIN, Role.AGENT, Role.VIEWER };
		int checked = 0;
		for (CompanyStatus company : CompanyStatus.values()) {
			for (Operation op : Operation.values()) {
				for (Role actor : Role.values()) {
					for (Role invited : invitedRoles) {
						Decision actual = InvitationRules.decide(op, actor, invited, company);
						Outcome want = expected(op, actor, invited, company);
						assertThat(actual.outcome()).as("%s by %s for %s in %s", op, actor, invited, company)
							.isEqualTo(want);
						assertThat(actual.recentAuthenticationRequired())
							.as("recent authentication for %s %s", op, invited)
							.isEqualTo(want == Outcome.ALLOWED && invited == Role.ADMIN && op != Operation.REVOKE);
						assertThat(actual.deniedPermission() != null).isEqualTo(want == Outcome.FORBIDDEN);
						checked++;
					}
				}
			}
		}
		assertThat(checked).isEqualTo(2 * 3 * 4 * 5);
	}

	@Test
	void ac17TheRoleGrantTable() {
		for (Role invited : new Role[] { Role.ADMIN, Role.AGENT, Role.VIEWER }) {
			assertThat(InvitationRules.mayGrant(Role.OWNER, invited)).as("Owner invites %s", invited).isTrue();
		}
		assertThat(InvitationRules.mayGrant(Role.ADMIN, Role.AGENT)).isTrue();
		assertThat(InvitationRules.mayGrant(Role.ADMIN, Role.VIEWER)).isTrue();
		assertThat(InvitationRules.mayGrant(Role.ADMIN, Role.ADMIN)).as("an Admin cannot create Admins").isFalse();
		for (Role actor : Role.values()) {
			assertThat(InvitationRules.mayGrant(actor, Role.OWNER)).as("%s inviting Owner", actor).isFalse();
			assertThat(InvitationRules.mayGrant(actor, null)).isFalse();
		}
		for (Role actor : new Role[] { Role.AGENT, Role.VIEWER }) {
			for (Role invited : Role.values()) {
				assertThat(InvitationRules.mayGrant(actor, invited)).as("%s inviting %s", actor, invited).isFalse();
			}
		}
	}

	@Test
	void ac27OnlyCreatingOrResendingAnAdminInvitationNeedsARecentLogin() {
		assertThat(InvitationRules.decide(Operation.CREATE, Role.OWNER, Role.ADMIN, CompanyStatus.ACTIVE))
			.extracting(Decision::outcome, Decision::recentAuthenticationRequired)
			.containsExactly(Outcome.ALLOWED, true);
		assertThat(InvitationRules.decide(Operation.RESEND, Role.OWNER, Role.ADMIN, CompanyStatus.ACTIVE)
			.recentAuthenticationRequired()).isTrue();
		assertThat(InvitationRules.decide(Operation.REVOKE, Role.OWNER, Role.ADMIN, CompanyStatus.ACTIVE)
			.recentAuthenticationRequired()).as("revoking is not on the list").isFalse();
		for (Role invited : new Role[] { Role.AGENT, Role.VIEWER }) {
			for (Operation op : Operation.values()) {
				assertThat(InvitationRules.decide(op, Role.OWNER, invited, CompanyStatus.ACTIVE)
					.recentAuthenticationRequired()).as("%s %s", op, invited).isFalse();
			}
		}
	}

	@Test
	void aCallerWhoMayNotInviteIsRefusedEvenWhenTheRequestIsMalformed() {
		for (Role actor : new Role[] { Role.AGENT, Role.VIEWER }) {
			assertThat(InvitationRules.decide(Operation.CREATE, actor, Role.OWNER, CompanyStatus.ACTIVE).outcome())
				.as("%s asking for Owner", actor).isEqualTo(Outcome.FORBIDDEN);
			assertThat(InvitationRules.decide(Operation.CREATE, actor, null, CompanyStatus.ACTIVE).outcome())
				.isEqualTo(Outcome.FORBIDDEN);
		}
		assertThat(InvitationRules.decide(Operation.CREATE, Role.OWNER, Role.OWNER, CompanyStatus.ACTIVE).outcome())
			.as("an Owner asking for Owner is a bad request").isEqualTo(Outcome.INVALID);
	}

	@Test
	void refusedWhateverTheRoleNamesTheRowOnlyForRolesThatCanNeverDoIt() {
		assertThat(InvitationRules.refusedWhateverTheRole(Operation.CREATE, Role.AGENT))
			.isEqualTo(Permission.INVITE_MEMBERS);
		assertThat(InvitationRules.refusedWhateverTheRole(Operation.RESEND, Role.VIEWER))
			.isEqualTo(Permission.REVOKE_OR_RESEND_INVITATIONS);
		assertThat(InvitationRules.refusedWhateverTheRole(Operation.REVOKE, Role.AGENT))
			.isEqualTo(Permission.REVOKE_OR_RESEND_INVITATIONS);
		for (Operation op : Operation.values()) {
			assertThat(InvitationRules.refusedWhateverTheRole(op, Role.OWNER)).as("Owner %s", op).isNull();
			assertThat(InvitationRules.refusedWhateverTheRole(op, Role.ADMIN)).as("Admin %s", op).isNull();
		}
	}

}
