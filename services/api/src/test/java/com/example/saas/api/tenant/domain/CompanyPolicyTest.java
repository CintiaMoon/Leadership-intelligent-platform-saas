package com.example.saas.api.tenant.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyPolicyTest {

	@Test
	void everyPermissionHasACellForEveryRole() {
		for (Permission permission : Permission.values()) {
			for (Role role : Role.values()) {
				assertThat(CompanyPolicy.grant(role, permission)).as("%s / %s", permission, role).isNotNull();
			}
		}
	}

	@Test
	void anOutrightGrantFollowsTheCellInAnActiveCompany() {
		for (Permission permission : Permission.values()) {
			for (Role role : Role.values()) {
				boolean expected = CompanyPolicy.grant(role, permission) == Grant.ALLOWED;
				assertThat(CompanyPolicy.permits(role, permission, CompanyStatus.ACTIVE, null))
					.as("%s / %s", permission, role)
					.isEqualTo(expected);
			}
		}
	}

	@Test
	void anAdminActsOnlyOnAgentsAndViewers() {
		for (Permission permission : new Permission[] { Permission.CHANGE_MEMBER_ROLE,
				Permission.SUSPEND_OR_REMOVE_MEMBERS, Permission.REACTIVATE_MEMBERS }) {
			assertThat(CompanyPolicy.permits(Role.ADMIN, permission, CompanyStatus.ACTIVE, Role.AGENT)).isTrue();
			assertThat(CompanyPolicy.permits(Role.ADMIN, permission, CompanyStatus.ACTIVE, Role.VIEWER)).isTrue();
			assertThat(CompanyPolicy.permits(Role.ADMIN, permission, CompanyStatus.ACTIVE, Role.ADMIN)).isFalse();
			assertThat(CompanyPolicy.permits(Role.ADMIN, permission, CompanyStatus.ACTIVE, Role.OWNER)).isFalse();
			assertThat(CompanyPolicy.permits(Role.ADMIN, permission, CompanyStatus.ACTIVE, null))
				.as("a conditional cell with no target is denied")
				.isFalse();
		}
	}

	@Test
	void anAdminCanViewButNeverChangeThePlan() {
		assertThat(CompanyPolicy.permits(Role.ADMIN, Permission.VIEW_PLAN, CompanyStatus.ACTIVE, null)).isTrue();
		assertThat(CompanyPolicy.permits(Role.ADMIN, Permission.MANAGE_PLANS, CompanyStatus.ACTIVE, null)).isFalse();
		assertThat(CompanyPolicy.permits(Role.OWNER, Permission.MANAGE_PLANS, CompanyStatus.ACTIVE, null)).isTrue();
	}

	@Test
	void noRoleMayPurgeCompanyData() {
		for (Role role : Role.values()) {
			assertThat(CompanyPolicy.permits(role, Permission.PURGE_COMPANY_DATA, CompanyStatus.ACTIVE, null)).isFalse();
		}
	}

	@Test
	void theOwnerCannotLeaveAndOnlyTheOwnerTransfersOwnership() {
		assertThat(CompanyPolicy.permits(Role.OWNER, Permission.LEAVE_COMPANY, CompanyStatus.ACTIVE, null)).isFalse();
		for (Role role : Role.values()) {
			assertThat(CompanyPolicy.permits(role, Permission.TRANSFER_OWNERSHIP, CompanyStatus.ACTIVE, null))
				.isEqualTo(role == Role.OWNER);
		}
	}

	@Test
	void whilePendingDeletionOnlyTheOwnerMayReadOrCancelTheDeletion() {
		for (Permission permission : Permission.values()) {
			for (Role role : Role.values()) {
				boolean expected = role == Role.OWNER && CompanyPolicy.grant(role, permission) == Grant.ALLOWED
						&& (!permission.stateChanging() || permission == Permission.CANCEL_COMPANY_DELETION);
				assertThat(CompanyPolicy.permits(role, permission, CompanyStatus.PENDING_DELETION, Role.AGENT))
					.as("%s / %s while pending deletion", permission, role)
					.isEqualTo(expected);
			}
		}
	}

}
