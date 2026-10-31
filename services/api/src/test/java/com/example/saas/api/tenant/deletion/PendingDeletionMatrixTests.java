package com.example.saas.api.tenant.deletion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.security.TestEndpointsConfiguration;
import com.example.saas.api.tenant.TenantTestEndpointsConfiguration;
import com.example.saas.api.tenant.TestLifecycle;
import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.Grant;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every row of the SPEC-TEN permission matrix against a company that is closing (SPEC-DEL AC-DEL-12, 14, 18, 29), over
 * HTTP through the test routes (D-116), one test per row. The expectations are written here from the spec, not derived
 * from {@code CompanyPolicy}: while a deletion is pending only the Owner reads and the only change that works is the Owner's
 * cancellation; once the purge is queued, running or failed only the Owner reads the company's details.
 */
@Import({ TestEndpointsConfiguration.class, TenantTestEndpointsConfiguration.class })
class PendingDeletionMatrixTests extends AuthIntegrationTest {

	/** The matrix rows that change data, as the spec lists them. */
	private static final Set<Permission> CHANGES = EnumSet.of(Permission.CREATE_CORE_RECORDS, Permission.EDIT_CORE_RECORDS,
			Permission.DELETE_CORE_RECORDS, Permission.INVITE_MEMBERS, Permission.REVOKE_OR_RESEND_INVITATIONS,
			Permission.CHANGE_MEMBER_ROLE, Permission.SUSPEND_OR_REMOVE_MEMBERS, Permission.REACTIVATE_MEMBERS,
			Permission.LEAVE_COMPANY, Permission.ASSIGN_OR_REVOKE_ADMIN, Permission.TRANSFER_OWNERSHIP,
			Permission.CANCEL_OWNERSHIP_TRANSFER, Permission.ACCEPT_OWNERSHIP_TRANSFER, Permission.MANAGE_SETTINGS,
			Permission.MANAGE_PLANS, Permission.REQUEST_COMPANY_DELETION, Permission.CANCEL_COMPANY_DELETION,
			Permission.PURGE_COMPANY_DATA);

	/** The rows that only read. */
	private static final Set<Permission> READS = EnumSet.of(Permission.VIEW_COMPANY_DETAILS, Permission.VIEW_CORE_RECORDS,
			Permission.VIEW_REPORTS, Permission.EXPORT_DATA, Permission.VIEW_MEMBER_LIST, Permission.VIEW_PLAN);

	@Autowired
	private TransactionTemplate tx;

	private record Member(UUID userId, Cookie cookie) {
	}

	private record Team(UUID companyId, Map<Role, Member> byRole) {
	}

	private Member member() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		return new Member(userId, sessionCookie(api.login(email, STRONG_PASSWORD, freshIp())));
	}

	/** One company with an ACTIVE Team plan and one signed-in member per role. */
	private Team team() throws Exception {
		Map<Role, Member> byRole = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			byRole.put(role, member());
		}
		UUID companyId = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Closing', now(), now()) RETURNING id), "
							+ "s AS (INSERT INTO company_subscription (company_id, plan_id, state, state_changed_at, created_at, updated_at) "
							+ "SELECT c.id, (SELECT id FROM plan WHERE code = 'TEAM' AND version = 1), 'ACTIVE', now(), now(), now() FROM c) "
							+ "SELECT id FROM c",
					UUID.class);
			for (Role role : Role.values()) {
				jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
						+ "VALUES (?, ?, ?, 'ACTIVE', now(), now())", id, byRole.get(role).userId(), role.name());
			}
			return id;
		});
		return new Team(companyId, byRole);
	}

	private static String route(Permission permission) {
		return permission.name().toLowerCase().replace('_', '-');
	}

	private int call(Member caller, UUID companyId, Permission permission) throws Exception {
		return api.get("/api/v1/companies/" + companyId + "/test/" + route(permission), caller.cookie(), freshIp())
			.getResponse()
			.getStatus();
	}

	@Test
	void theTwoListsStateTheWholeMatrixAndAgreeWithTheFlagOnEachRow() {
		Set<Permission> all = EnumSet.copyOf(CHANGES);
		all.addAll(READS);
		assertThat(all).as("every row is listed once").containsExactlyInAnyOrder(Permission.values());
		assertThat(CHANGES).as("no row is both").doesNotContainAnyElementsOf(READS);
		for (Permission permission : Permission.values()) {
			assertThat(permission.stateChanging()).as(permission.name()).isEqualTo(CHANGES.contains(permission));
		}
	}

	/** AC-DEL-12, 14, 18: one test per row while the deletion is pending. */
	@TestFactory
	List<DynamicTest> everyRowOfTheMatrixWhileTheDeletionIsPending() throws Exception {
		Team team = team();
		TestLifecycle.markPendingDeletion(jdbc, team.companyId());
		List<DynamicTest> tests = new ArrayList<>();
		for (Permission permission : Permission.values()) {
			tests.add(DynamicTest.dynamicTest("pending deletion / " + permission, () -> {
				for (Role role : Role.values()) {
					boolean ownerReads = role == Role.OWNER && READS.contains(permission)
							&& CompanyPolicy.grant(role, permission) == Grant.ALLOWED;
					boolean ownerCancels = role == Role.OWNER && permission == Permission.CANCEL_COMPANY_DELETION;
					assertThat(call(team.byRole().get(role), team.companyId(), permission))
						.as("%s / %s", permission, role)
						.isEqualTo(ownerReads || ownerCancels ? 200 : 403);
				}
			}));
		}
		return tests;
	}

	/** AC-DEL-29: once the purge is queued, running or failed, nobody does anything but the Owner reading the details. */
	@TestFactory
	List<DynamicTest> everyRowOfTheMatrixOnceThePurgeIsQueuedRunningOrFailed() throws Exception {
		List<DynamicTest> tests = new ArrayList<>();
		for (String state : List.of("PURGE_QUEUED", "PURGING", "PURGE_FAILED")) {
			Team team = team();
			TestLifecycle.markPendingDeletion(jdbc, team.companyId());
			jdbc.update("UPDATE company SET status = 'PURGE_QUEUED' WHERE id = ?", team.companyId());
			if (!state.equals("PURGE_QUEUED")) {
				jdbc.update("UPDATE company SET status = 'PURGING', purge_claimed_until = now() + interval '5 minutes' "
						+ "WHERE id = ?", team.companyId());
			}
			if (state.equals("PURGE_FAILED")) {
				jdbc.update("UPDATE company SET status = 'PURGE_FAILED', purge_claimed_until = NULL, "
						+ "purge_error_code = 'HANDLER_FAILED', purge_attempts = 1 WHERE id = ?", team.companyId());
			}
			for (Permission permission : Permission.values()) {
				tests.add(DynamicTest.dynamicTest(state + " / " + permission, () -> {
					for (Role role : Role.values()) {
						boolean allowed = role == Role.OWNER && permission == Permission.VIEW_COMPANY_DETAILS;
						assertThat(call(team.byRole().get(role), team.companyId(), permission))
							.as("%s / %s / %s", state, permission, role)
							.isEqualTo(allowed ? 200 : 403);
					}
				}));
			}
		}
		return tests;
	}

}
