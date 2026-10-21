package com.example.saas.api.tenant;

import java.util.UUID;

import com.example.saas.api.auth.AuthIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tenant invariants that hold at the database even if application code is wrong (CLAUDE.md principle 9). */
class TenantSchemaTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private UUID newCompany() {
		return jdbc.queryForObject(
				"INSERT INTO company (name, created_at, updated_at) VALUES ('Acme', now(), now()) RETURNING id",
				UUID.class);
	}

	private void addMember(UUID companyId, UUID userId, String role, String status) {
		jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", companyId, userId, role, status);
	}

	/** A company with its Owner, created the way the service will: one transaction. */
	private UUID companyWithOwner(UUID ownerId) {
		return tx.execute(s -> {
			UUID companyId = newCompany();
			addMember(companyId, ownerId, "OWNER", "ACTIVE");
			return companyId;
		});
	}

	@Test
	void aCompanyAndItsOwnerCommitTogether() {
		UUID companyId = companyWithOwner(newUser());

		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER'", companyId))
			.isEqualTo(1);
	}

	@Test
	void aCompanyWithoutAnOwnerCannotCommit() {
		int before = count("SELECT count(*) FROM company");

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> newCompany()))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count("SELECT count(*) FROM company")).isEqualTo(before);
	}

	@Test
	void aFailureAfterTheCompanyInsertLeavesNothing() {
		UUID owner = newUser();
		int companies = count("SELECT count(*) FROM company");
		int memberships = count("SELECT count(*) FROM membership");

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			UUID companyId = newCompany();
			addMember(companyId, owner, "OWNER", "ACTIVE");
			throw new IllegalStateException("forced failure");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("SELECT count(*) FROM company")).isEqualTo(companies);
		assertThat(count("SELECT count(*) FROM membership")).isEqualTo(memberships);
	}

	@Test
	void theOwnerCannotBeDemotedRemovedOrDeletedWithoutAReplacementInTheSameTransaction() {
		UUID companyId = companyWithOwner(newUser());

		assertThatThrownBy(() -> jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ?", companyId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(
				() -> jdbc.update("UPDATE membership SET status = 'REMOVED' WHERE company_id = ?", companyId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("DELETE FROM membership WHERE company_id = ?", companyId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ownershipCanMoveInOneTransaction() {
		UUID owner = newUser();
		UUID successor = newUser();
		UUID companyId = companyWithOwner(owner);
		addMember(companyId, successor, "ADMIN", "ACTIVE");

		tx.executeWithoutResult(s -> {
			jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ? AND user_id = ?", companyId, owner);
			jdbc.update("UPDATE membership SET role = 'OWNER' WHERE company_id = ? AND user_id = ?", companyId,
					successor);
		});

		assertThat(jdbc.queryForObject("SELECT user_id FROM membership WHERE company_id = ? AND role = 'OWNER'",
				UUID.class, companyId))
			.isEqualTo(successor);
	}

	@Test
	void aSuspendedOwnerStillHoldsTheRole() {
		UUID companyId = companyWithOwner(newUser());

		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE company_id = ?", companyId);

		assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE company_id = ?", String.class, companyId))
			.isEqualTo("SUSPENDED");
	}

	@Test
	void aCompanyHasAtMostOneOwner() {
		UUID companyId = companyWithOwner(newUser());

		assertThatThrownBy(() -> addMember(companyId, newUser(), "OWNER", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> addMember(companyId, newUser(), "OWNER", "SUSPENDED"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aUserHasOneLiveMembershipPerCompanyButMayReturnAfterRemoval() {
		UUID companyId = companyWithOwner(newUser());
		UUID member = newUser();
		addMember(companyId, member, "AGENT", "ACTIVE");

		assertThatThrownBy(() -> addMember(companyId, member, "VIEWER", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> addMember(companyId, member, "VIEWER", "SUSPENDED"))
			.isInstanceOf(DataIntegrityViolationException.class);

		// The same user can belong to another company at the same time.
		companyWithOwner(member);

		jdbc.update("UPDATE membership SET status = 'REMOVED' WHERE company_id = ? AND user_id = ?", companyId, member);
		addMember(companyId, member, "VIEWER", "ACTIVE");

		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", companyId, member))
			.isEqualTo(2);
	}

	@Test
	void roleStatusAndNameAreChecked() {
		UUID companyId = companyWithOwner(newUser());

		assertThatThrownBy(() -> addMember(companyId, newUser(), "SUPERUSER", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> addMember(companyId, newUser(), "AGENT", "BANNED"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(
				() -> jdbc.update("INSERT INTO company (name, created_at, updated_at) VALUES ('', now(), now())"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc
			.update("INSERT INTO company (name, created_at, updated_at) VALUES (?, now(), now())", "x".repeat(101)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO company (name, status, created_at, updated_at) VALUES ('A', 'GONE', now(), now())"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void membershipsNeedARealCompanyAndUser() {
		UUID companyId = companyWithOwner(newUser());

		assertThatThrownBy(() -> addMember(UUID.randomUUID(), newUser(), "AGENT", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> addMember(companyId, UUID.randomUUID(), "AGENT", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aCompanyCannotBeDeletedWhileMembershipsExist() {
		UUID companyId = companyWithOwner(newUser());

		assertThatThrownBy(() -> jdbc.update("DELETE FROM company WHERE id = ?", companyId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ?", companyId)).isEqualTo(1);
	}

	@Test
	void aCompanyAndAllItsMembershipsCanBePurgedInOneTransaction() {
		UUID companyId = companyWithOwner(newUser());

		tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM membership WHERE company_id = ?", companyId);
			jdbc.update("DELETE FROM company WHERE id = ?", companyId);
		});

		assertThat(count("SELECT count(*) FROM company WHERE id = ?", companyId)).isZero();
	}

	@Test
	void aMembershipCannotMoveToAnotherCompany() {
		UUID owner = newUser();
		UUID source = companyWithOwner(owner);
		UUID target = companyWithOwner(newUser());

		// Moving the Owner would leave the source without one; moving anyone else would graft a member onto a tenant.
		assertThatThrownBy(() -> jdbc.update("UPDATE membership SET company_id = ? WHERE company_id = ? AND user_id = ?",
				target, source, owner))
			.isInstanceOf(DataIntegrityViolationException.class);
		UUID member = newUser();
		addMember(source, member, "AGENT", "ACTIVE");
		assertThatThrownBy(() -> jdbc.update("UPDATE membership SET company_id = ? WHERE company_id = ? AND user_id = ?",
				target, source, member))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ?", source, owner))
			.isEqualTo(1);
	}

}
