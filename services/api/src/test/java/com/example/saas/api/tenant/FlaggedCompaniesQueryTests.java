package com.example.saas.api.tenant;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.AuthIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** The operator query that lists flagged companies and how many members could take over (D-142). */
class FlaggedCompaniesQueryTests extends AuthIntegrationTest {

	private static final Path QUERY = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"list-flagged-companies.sql");

	@Autowired
	private TransactionTemplate tx;

	private UUID user() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private UUID company(String ownerStatus, boolean flagged, String... otherRolesAndStatuses) {
		return tx.execute(s -> {
			UUID id = jdbc.queryForObject("INSERT INTO company (name, ownership_resolution_at, created_at, updated_at) "
					+ "VALUES ('Flagged', " + (flagged ? "now()" : "NULL") + ", now(), now()) RETURNING id", UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', ?, now(), now())", id, user(), ownerStatus);
			for (String roleAndStatus : otherRolesAndStatuses) {
				String[] parts = roleAndStatus.split(":");
				jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, now(), now())", id, user(), parts[0], parts[1]);
			}
			return id;
		});
	}

	private List<Map<String, Object>> run() throws Exception {
		List<String> statements = RunbookSql.statements(QUERY, Map.of());
		assertThat(statements).hasSize(1);
		String sql = statements.get(0).strip();
		return jdbc.queryForList(sql.substring(0, sql.length() - 1));
	}

	private Map<String, Object> rowFor(List<Map<String, Object>> rows, UUID companyId) {
		return rows.stream().filter(r -> r.get("company_id").equals(companyId)).findFirst().orElse(null);
	}

	@Test
	void listsOnlyFlaggedCompaniesWithTheNumberOfMembersWhoCouldTakeOver() throws Exception {
		UUID withAdmin = company("SUSPENDED", true, "ADMIN:ACTIVE", "AGENT:ACTIVE", "VIEWER:SUSPENDED");
		UUID agentsOnly = company("SUSPENDED", true, "AGENT:ACTIVE", "VIEWER:REMOVED");
		UUID soleMember = company("SUSPENDED", true);
		UUID notFlagged = company("ACTIVE", false, "ADMIN:ACTIVE");

		List<Map<String, Object>> rows = run();

		assertThat(rowFor(rows, notFlagged)).as("a company that is not flagged is not listed").isNull();
		assertThat(rowFor(rows, withAdmin)).containsEntry("other_active_members", 2L).containsEntry("active_admins", 1L);
		assertThat(rowFor(rows, agentsOnly)).containsEntry("other_active_members", 1L)
			.containsEntry("active_admins", 0L);
		assertThat(rowFor(rows, soleMember)).as("the Owner was the only member")
			.containsEntry("other_active_members", 0L)
			.containsEntry("active_admins", 0L);
	}

}
