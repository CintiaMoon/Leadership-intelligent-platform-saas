package com.example.saas.api.tenant;

import java.util.UUID;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.persistence.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What migration V12 adds: status records, audit before/after values, the ownership-resolution flag. */
class MembershipSchemaV12Tests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private AuditLog audit;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private UUID companyWithOwner(UUID owner) {
		return tx.execute(s -> {
			UUID companyId = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('V12', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", companyId, owner);
			return companyId;
		});
	}

	@Test
	void theNewColumnsAreNullableSoExistingRowsAndNewMembershipsNeedNoValue() {
		UUID owner = newUser();
		UUID companyId = companyWithOwner(owner);

		assertThat(jdbc.queryForObject("SELECT status_changed_at IS NULL AND status_changed_by IS NULL "
				+ "FROM membership WHERE company_id = ?", Boolean.class, companyId)).isTrue();
		assertThat(jdbc.queryForObject("SELECT ownership_resolution_at IS NULL FROM company WHERE id = ?",
				Boolean.class, companyId)).isTrue();
	}

	@Test
	void whoChangedTheStatusMustBeARealUser() {
		UUID owner = newUser();
		UUID companyId = companyWithOwner(owner);

		jdbc.update("UPDATE membership SET status_changed_by = ?, status_changed_at = now() WHERE company_id = ?",
				owner, companyId);
		assertThatThrownBy(() -> jdbc.update("UPDATE membership SET status_changed_by = ? WHERE company_id = ?",
				UUID.randomUUID(), companyId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anAuditRowCarriesBeforeAndAfterValuesThatAreEnumNamesOnly() {
		UUID actor = newUser();
		UUID companyId = companyWithOwner(actor);
		UUID target = UUID.randomUUID();

		tx.executeWithoutResult(s -> audit.recordChange("membership.role.changed", AuditLog.Actor.user, actor,
				"membership", target, companyId, "success", null, "AGENT", "ADMIN"));

		assertThat(jdbc.queryForObject("SELECT before_value || '>' || after_value FROM audit_event "
				+ "WHERE event_type = 'membership.role.changed' AND target_id = ?", String.class, target))
			.isEqualTo("AGENT>ADMIN");
		for (String bad : new String[] { "admin", "a name", "x@example.com", "A".repeat(33), "", "AGENT\nADMIN" }) {
			assertThatThrownBy(() -> jdbc.update("INSERT INTO audit_event (occurred_at, event_type, actor_type, "
					+ "target_type, outcome, before_value) VALUES (now(), 'membership.role.changed', 'user', "
					+ "'membership', 'success', ?)", bad))
				.as("before_value %s", bad.length())
				.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update("INSERT INTO audit_event (occurred_at, event_type, actor_type, "
					+ "target_type, outcome, after_value) VALUES (now(), 'membership.role.changed', 'user', "
					+ "'membership', 'success', ?)", bad))
				.as("after_value %s", bad.length())
				.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void existingAuditCallsStillWorkWithoutValues() {
		UUID actor = newUser();
		UUID companyId = companyWithOwner(actor);
		UUID target = UUID.randomUUID();

		tx.executeWithoutResult(s -> audit.recordForCompany("tenant.company.created", AuditLog.Actor.user, actor,
				"company", target, companyId, "success", null));

		assertThat(jdbc.queryForObject("SELECT before_value IS NULL AND after_value IS NULL FROM audit_event "
				+ "WHERE target_id = ?", Boolean.class, target)).isTrue();
	}

	@Test
	void auditRowsStillCannotBeUpdated() {
		UUID actor = newUser();
		UUID companyId = companyWithOwner(actor);
		UUID target = UUID.randomUUID();
		tx.executeWithoutResult(s -> audit.recordChange("membership.suspended", AuditLog.Actor.user, actor,
				"membership", target, companyId, "success", null, "ACTIVE", "SUSPENDED"));

		assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET after_value = 'ACTIVE' WHERE target_id = ?", target))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}

}
