package com.example.saas.api.tenant.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.saas.api.plan.domain.SubscriptionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares {@link CompanyPolicy} with the "Gating by subscription state" table of SPEC-PLAN, every permission in every
 * state for every role (AC-PLAN-24, plan Q-20), so changing the spec without the code, or a cell of the code without the
 * spec, fails the build.
 */
class StateGatingParityTest {

	private static final Path SPEC = Path.of("..", "..", "docs", "specs", "006-plans-entitlements", "spec.md");

	private static final String HEADING = "## Gating by subscription state";

	/** What the state does to a row, from the words of a cell. The role matrix always applies on top. */
	private enum Cell {

		/** "per matrix ...", "allowed", "allowed (Owner)", "Owner row ...": the state takes nothing away. */
		MATRIX,

		/** "rejected". */
		REJECTED,

		/** "Owner only": the Owner as the matrix says, nobody else. */
		OWNER_ONLY

	}

	static Cell parse(String cell) {
		String text = cell.strip();
		if (text.equals("rejected")) {
			return Cell.REJECTED;
		}
		if (text.equals("Owner only")) {
			return Cell.OWNER_ONLY;
		}
		if (text.startsWith("per matrix") || text.startsWith("per the member limit") || text.startsWith("allowed")
				|| text.startsWith("Owner row") || text.equals("no endpoint")) {
			return Cell.MATRIX;
		}
		throw new AssertionError("Unrecognized gating cell in the spec: '" + text + "'");
	}

	/** The permissions each spec row covers. The two invitation rows that share a permission are handled apart. */
	private static final Map<String, List<Permission>> ROWS = new LinkedHashMap<>();

	static {
		ROWS.put("Reads: company details, core records, reports, member list, plan",
				List.of(Permission.VIEW_COMPANY_DETAILS, Permission.VIEW_CORE_RECORDS, Permission.VIEW_REPORTS,
						Permission.VIEW_MEMBER_LIST, Permission.VIEW_PLAN));
		ROWS.put("Create, edit, delete core records; manage settings",
				List.of(Permission.CREATE_CORE_RECORDS, Permission.EDIT_CORE_RECORDS, Permission.DELETE_CORE_RECORDS,
						Permission.MANAGE_SETTINGS));
		ROWS.put("Export data", List.of(Permission.EXPORT_DATA));
		ROWS.put("Invite; resend an invitation", List.of(Permission.INVITE_MEMBERS, Permission.REVOKE_OR_RESEND_INVITATIONS));
		ROWS.put("Revoke an invitation", List.of());
		ROWS.put("Accept an invitation (adds a member)", List.of());
		ROWS.put("Change role; assign or revoke Admin",
				List.of(Permission.CHANGE_MEMBER_ROLE, Permission.ASSIGN_OR_REVOKE_ADMIN));
		ROWS.put("Reactivate a member (adds a member)", List.of(Permission.REACTIVATE_MEMBERS));
		ROWS.put("Suspend or remove a member", List.of(Permission.SUSPEND_OR_REMOVE_MEMBERS));
		ROWS.put("Leave the company", List.of(Permission.LEAVE_COMPANY));
		ROWS.put("Transfer ownership: start, accept, cancel",
				List.of(Permission.TRANSFER_OWNERSHIP, Permission.ACCEPT_OWNERSHIP_TRANSFER,
						Permission.CANCEL_OWNERSHIP_TRANSFER));
		ROWS.put("Request or cancel company deletion",
				List.of(Permission.REQUEST_COMPANY_DELETION, Permission.CANCEL_COMPANY_DELETION));
		ROWS.put("Manage plans", List.of(Permission.MANAGE_PLANS));
	}

	private static Cell column(Map<String, String[]> table, String row, SubscriptionState state) {
		String[] cells = table.get(row);
		assertThat(cells).as("the spec row '%s'", row).isNotNull();
		int index = switch (state) {
			case TRIALING, ACTIVE -> 0;
			case EXPIRED_READ_ONLY, NO_PLAN -> 1;
			case EXPIRED_LOCKED -> 2;
		};
		return parse(cells[index]);
	}

	private static Map<String, String[]> readTable() throws IOException {
		List<String> lines = Files.readAllLines(SPEC);
		int start = lines.indexOf(lines.stream().filter(l -> l.startsWith(HEADING)).findFirst().orElseThrow());
		Map<String, String[]> table = new LinkedHashMap<>();
		boolean header = true;
		for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) {
			String line = lines.get(i);
			if (!line.startsWith("|")) {
				continue;
			}
			String[] parts = line.substring(1, line.length() - 1).split("\\|");
			if (header) {
				// The header row and the separator row.
				assertThat(parts[1].strip()).isEqualTo("TRIALING, ACTIVE");
				header = false;
				continue;
			}
			if (parts[0].strip().startsWith("---")) {
				continue;
			}
			table.put(parts[0].strip(), new String[] { parts[1], parts[2], parts[3] });
		}
		return table;
	}

	private static boolean expected(Cell cell, Role role, boolean matrixAllows) {
		return switch (cell) {
			case MATRIX -> matrixAllows;
			case REJECTED -> false;
			case OWNER_ONLY -> matrixAllows && role == Role.OWNER;
		};
	}

	@Test
	void everyRowOfTheSpecTableIsKnownAndNoPermissionIsLeftOut() throws IOException {
		Map<String, String[]> table = readTable();
		assertThat(table.keySet()).containsExactlyInAnyOrderElementsOf(ROWS.keySet());
		EnumSet<Permission> covered = EnumSet.noneOf(Permission.class);
		ROWS.values().forEach(covered::addAll);
		// Purge is a system process with no role; the invitation rows share the "revoke or resend" permission.
		EnumSet<Permission> missing = EnumSet.complementOf(covered);
		missing.remove(Permission.PURGE_COMPANY_DATA);
		assertThat(missing).as("permissions with no row in the gating table").isEmpty();
	}

	@Test
	void theCodeMatchesTheSpecTableForEveryPermissionStateAndRole() throws IOException {
		Map<String, String[]> table = readTable();
		List<String> mismatches = new ArrayList<>();
		int checked = 0;
		for (Map.Entry<String, List<Permission>> row : ROWS.entrySet()) {
			for (Permission permission : row.getValue()) {
				for (SubscriptionState state : SubscriptionState.values()) {
					Cell cell = column(table, row.getKey(), state);
					for (Role role : Role.values()) {
						boolean matrix = CompanyPolicy.permits(role, permission, CompanyStatus.ACTIVE, Role.AGENT);
						boolean want = expected(cell, role, matrix);
						boolean got = CompanyPolicy.permits(role, permission, CompanyStatus.ACTIVE, state, Role.AGENT);
						checked++;
						if (want != got) {
							mismatches.add(permission + " / " + state + " / " + role + ": spec " + cell + " gives " + want
									+ ", code gives " + got);
						}
					}
				}
			}
		}
		// Every cell was compared: permissions in the table x states x roles.
		assertThat(checked).isEqualTo(ROWS.values().stream().mapToInt(List::size).sum() * SubscriptionState.values().length
				* Role.values().length);
		assertThat(mismatches).isEmpty();
	}

	@Test
	void revokingAndAcceptingAnInvitationFollowTheirOwnRows() throws IOException {
		Map<String, String[]> table = readTable();
		for (SubscriptionState state : SubscriptionState.values()) {
			Cell revoke = column(table, "Revoke an invitation", state);
			assertThat(CompanyPolicy.stateAllowsRevokingInvitation(state)).as("revoke in %s", state)
				.isEqualTo(revoke != Cell.REJECTED);
			Cell accept = column(table, "Accept an invitation (adds a member)", state);
			assertThat(CompanyPolicy.stateAllowsInvitationAcceptance(state)).as("accept in %s", state)
				.isEqualTo(accept != Cell.REJECTED);
		}
	}

	@Test
	void theStateOnlyTakesAwayAndNeverGrantsWhatTheRoleLacks() {
		for (Permission permission : Permission.values()) {
			for (SubscriptionState state : SubscriptionState.values()) {
				for (Role role : Role.values()) {
					if (!CompanyPolicy.permits(role, permission, CompanyStatus.ACTIVE, Role.AGENT)) {
						assertThat(CompanyPolicy.permits(role, permission, CompanyStatus.ACTIVE, state, Role.AGENT))
							.as("%s / %s / %s", permission, state, role)
							.isFalse();
					}
				}
			}
		}
	}

	@Test
	void aRoleRefusalIsReportedAsTheRoleWhateverTheState() {
		for (SubscriptionState state : SubscriptionState.values()) {
			assertThat(CompanyPolicy.check(Role.VIEWER, Permission.INVITE_MEMBERS, CompanyStatus.ACTIVE, state, null))
				.isEqualTo(CompanyPolicy.Refusal.ROLE);
		}
		assertThat(CompanyPolicy.check(Role.OWNER, Permission.INVITE_MEMBERS, CompanyStatus.ACTIVE,
				SubscriptionState.EXPIRED_READ_ONLY, null))
			.isEqualTo(CompanyPolicy.Refusal.PLAN_STATE);
		assertThat(CompanyPolicy.check(Role.OWNER, Permission.INVITE_MEMBERS, CompanyStatus.ACTIVE,
				SubscriptionState.TRIALING, null))
			.isEqualTo(CompanyPolicy.Refusal.NONE);
	}

	@Test
	void aMissingStateIsRefused() {
		for (Permission permission : Permission.values()) {
			assertThat(CompanyPolicy.stateAllows(permission, Role.OWNER, null)).as(permission.name()).isFalse();
		}
	}

}
