package com.example.saas.api.invitation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.invitation.persistence.Invitation;
import com.example.saas.api.invitation.persistence.InvitationRepository;
import com.example.saas.api.tenant.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the invitation table guarantees even if application code is wrong (CLAUDE.md principle 9; migration V13). */
class InvitationSchemaTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private InvitationRepository invitations;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private record Seed(UUID companyId, UUID inviter) {
	}

	private Seed company() {
		return tx.execute(s -> {
			UUID inviter = newUser();
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Inv', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
					+ "VALUES (?, ?, 'OWNER', 'ACTIVE', now(), now())", id, inviter);
			return new Seed(id, inviter);
		});
	}

	private static final String INSERT = "INSERT INTO invitation (company_id, email_normalized, email_original, role, "
			+ "status, token_hash, invited_by, expires_at, created_at, updated_at) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, now() + interval '7 days', now(), now())";

	private void insert(Seed seed, String email, String role, String status, String tokenHash) {
		jdbc.update(INSERT, seed.companyId(), email, email, role, status, tokenHash, seed.inviter());
	}

	/** 64 lower-case hex characters, different every time: the tests share one database. */
	private static String hash() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
	}

	private static String address() {
		return "guest." + UUID.randomUUID() + "@example.com";
	}

	@Test
	void aPendingInvitationWithoutATokenYetIsValid() {
		Seed seed = company();

		insert(seed, address(), "AGENT", "PENDING", null);

		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND token_hash IS NULL",
				seed.companyId())).isEqualTo(1);
	}

	@Test
	void nobodyIsInvitedAsOwnerAndRoleAndStatusAreChecked() {
		Seed seed = company();

		assertThatThrownBy(() -> insert(seed, address(), "OWNER", "PENDING", null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed, address(), "SUPERUSER", "PENDING", null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed, address(), "AGENT", "WAITING", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theAddressIsStoredNormalizedAndBounded() {
		Seed seed = company();

		assertThatThrownBy(() -> insert(seed, "Mixed.Case@example.com", "AGENT", "PENDING", null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed, "x@", "AGENT", "PENDING", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aTokenHashIsLowerCaseHexOf64CharactersAndNeverStoredAsAToken() {
		Seed seed = company();

		for (String bad : new String[] { "short", "G".repeat(64), "A".repeat(64), "a".repeat(63), "a".repeat(65),
				"a".repeat(43) }) {
			assertThatThrownBy(() -> insert(seed, address(), "AGENT", "PENDING", bad)).as("hash of length %d", bad.length())
				.isInstanceOf(DataIntegrityViolationException.class);
		}
		insert(seed, address(), "AGENT", "PENDING", hash());
	}

	@Test
	void aTokenHashIdentifiesAtMostOneInvitationAcrossAllCompanies() {
		Seed first = company();
		Seed second = company();
		String shared = hash();
		insert(first, address(), "AGENT", "PENDING", shared);

		assertThatThrownBy(() -> insert(second, address(), "VIEWER", "PENDING", shared))
			.isInstanceOf(DataIntegrityViolationException.class);
		insert(second, address(), "VIEWER", "PENDING", null);
		insert(second, address(), "VIEWER", "PENDING", null);
	}

	@Test
	void onlyAPendingInvitationCanHoldATokenAndTheOutcomeFieldsMatchTheStatus() {
		Seed seed = company();
		String email = address();
		insert(seed, email, "AGENT", "PENDING", hash());
		UUID id = jdbc.queryForObject("SELECT id FROM invitation WHERE email_normalized = ?", UUID.class, email);

		assertThatThrownBy(() -> jdbc.update("UPDATE invitation SET status = 'REVOKED', revoked_at = now() WHERE id = ?", id))
			.as("a revoked invitation must drop its token")
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE invitation SET status = 'ACCEPTED', token_hash = NULL WHERE id = ?", id))
			.as("an accepted invitation records who and when")
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE invitation SET status = 'REVOKED', token_hash = NULL WHERE id = ?", id))
			.as("a revoked invitation records when")
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("UPDATE invitation SET status = 'REVOKED', token_hash = NULL, revoked_at = now() WHERE id = ?", id);
		assertThatThrownBy(() -> jdbc.update("UPDATE invitation SET token_hash = ? WHERE id = ?", hash(), id))
			.as("a token cannot be put back on a revoked invitation")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aninvitationMustExpireAfterItWasCreated() {
		Seed seed = company();

		assertThatThrownBy(() -> jdbc.update("INSERT INTO invitation (company_id, email_normalized, email_original, role, "
				+ "invited_by, expires_at, created_at, updated_at) VALUES (?, ?, ?, 'AGENT', ?, now() - interval '1 day', "
				+ "now(), now())", seed.companyId(), address(), "x@example.com", seed.inviter()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void atMostOnePendingInvitationExistsPerCompanyAndAddressButHistoryAndOtherCompaniesAreFine() {
		Seed seed = company();
		Seed other = company();
		String email = address();
		insert(seed, email, "AGENT", "PENDING", null);

		assertThatThrownBy(() -> insert(seed, email, "VIEWER", "PENDING", null))
			.as("a second pending invitation for the same company and address")
			.isInstanceOf(DataIntegrityViolationException.class);
		insert(other, email, "VIEWER", "PENDING", null);

		jdbc.update("UPDATE invitation SET status = 'EXPIRED' WHERE company_id = ? AND email_normalized = ?",
				seed.companyId(), email);
		insert(seed, email, "VIEWER", "PENDING", null);
		jdbc.update("UPDATE invitation SET status = 'REVOKED', revoked_at = now() WHERE company_id = ? "
				+ "AND email_normalized = ? AND status = 'PENDING'", seed.companyId(), email);
		insert(seed, email, "ADMIN", "PENDING", null);

		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ?",
				seed.companyId(), email)).as("history is kept").isEqualTo(3);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ? "
				+ "AND status = 'PENDING'", seed.companyId(), email)).isEqualTo(1);
	}

	@Test
	void parallelInsertsOfTheSamePendingInvitationLeaveExactlyOne() throws Exception {
		Seed seed = company();
		String email = address();
		List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> {
				try {
					insert(seed, email, "AGENT", "PENDING", null);
					return true;
				}
				catch (DataIntegrityViolationException e) {
					return false;
				}
			});
		}

		List<Boolean> results = com.example.saas.api.tenant.Parallel.runAll(tasks);

		assertThat(results.stream().filter(r -> r).count()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM invitation WHERE company_id = ? AND email_normalized = ?",
				seed.companyId(), email)).isEqualTo(1);
	}

	@Test
	void theCompanyAndTheUsersMustExistAndACompanyWithInvitationsCannotBeDeletedByAccident() {
		Seed seed = company();
		String email = address();

		assertThatThrownBy(() -> jdbc.update(INSERT, UUID.randomUUID(), email, email, "AGENT", "PENDING", null,
				seed.inviter())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(INSERT, seed.companyId(), email, email, "AGENT", "PENDING", null,
				UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
		insert(seed, email, "AGENT", "PENDING", null);
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			jdbc.update("DELETE FROM membership WHERE company_id = ?", seed.companyId());
			jdbc.update("DELETE FROM company WHERE id = ?", seed.companyId());
		})).as("the purge (slice 007) must delete the invitations too").isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theRepositoryMapsTheTableAndFindsAnInvitationByItsTokenHashOnlyWhilePending() {
		Seed seed = company();
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		Invitation saved = tx.execute(s -> invitations.save(Invitation.issue(seed.companyId(), "a@example.com",
				"A@Example.com", Role.AGENT, seed.inviter(), now, now.plus(7, ChronoUnit.DAYS))));

		Invitation loaded = invitations.findByCompanyIdAndId(seed.companyId(), saved.getId()).orElseThrow();
		assertThat(loaded.getEmailNormalized()).isEqualTo("a@example.com");
		assertThat(loaded.getEmailOriginal()).isEqualTo("A@Example.com");
		assertThat(loaded.getRole()).isEqualTo(Role.AGENT);
		assertThat(loaded.getTokenHash()).as("no token until delivery").isNull();
		String mine = hash();
		assertThat(invitations.findCompanyIdByTokenHash(mine)).isEmpty();

		jdbc.update("UPDATE invitation SET token_hash = ? WHERE id = ?", mine, saved.getId());
		assertThat(invitations.findCompanyIdByTokenHash(mine)).contains(seed.companyId());
		assertThat(invitations.findCompanyIdByTokenHash(hash())).isEmpty();
		jdbc.update("UPDATE invitation SET status = 'EXPIRED', token_hash = NULL WHERE id = ?", saved.getId());
		assertThat(invitations.findCompanyIdByTokenHash(mine)).as("a dead token finds nothing").isEmpty();
	}

	@Test
	void theRepositoryNeverReturnsAnotherCompanysInvitation() {
		Seed mine = company();
		Seed theirs = company();
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		Invitation theirInvitation = tx.execute(s -> invitations.save(Invitation.issue(theirs.companyId(),
				"b@example.com", "b@example.com", Role.VIEWER, theirs.inviter(), now, now.plus(7, ChronoUnit.DAYS))));

		Optional<Invitation> viaMyCompany = invitations.findByCompanyIdAndId(mine.companyId(), theirInvitation.getId());

		assertThat(viaMyCompany).isEmpty();
		assertThat(invitations.findByCompanyIdAndEmailNormalizedAndStatus(mine.companyId(), "b@example.com",
				com.example.saas.api.invitation.domain.InvitationStatus.PENDING)).isEmpty();
	}

}
