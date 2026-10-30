package com.example.saas.api.tenant;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a company into the lifecycle state a test needs, with the deletion request the database now demands (V17): who
 * asked (the company's Owner), when, and a deadline 30 days later. Tests that only need "a company that is pending
 * deletion" use this instead of setting the status alone.
 */
public final class TestLifecycle {

	private TestLifecycle() {
	}

	/** ACTIVE to PENDING_DELETION, as a deletion request does. The company must already have its Owner membership. */
	public static void markPendingDeletion(JdbcTemplate jdbc, UUID companyId) {
		int updated = jdbc.update("UPDATE company SET status = 'PENDING_DELETION', deletion_requested_at = now(), "
				+ "purge_at = now() + interval '30 days', deletion_requested_by = (SELECT m.user_id FROM membership m "
				+ "WHERE m.company_id = company.id AND m.role = 'OWNER' AND m.status IN ('ACTIVE', 'SUSPENDED') LIMIT 1) "
				+ "WHERE id = ?", companyId);
		if (updated != 1) {
			throw new IllegalStateException("No company " + companyId);
		}
	}

}
