package com.example.saas.api.tenant.ownership;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferLocks;
import com.example.saas.api.tenant.ownership.domain.TransferResolution;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransfer;
import com.example.saas.api.tenant.ownership.persistence.OwnershipTransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the ownership transfer table and the Owner invariant guarantee even if application code is wrong (migration V14). */
class OwnershipTransferSchemaTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private OwnershipTransferRepository transfers;

	@Autowired
	private OwnershipTransferLocks transferLocks;

	@Autowired
	private CompanyLocks companyLocks;

	private UUID newUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private record Seed(UUID companyId, UUID owner, UUID member) {
	}

	/** A company with its Owner and one active Admin who could receive the ownership. */
	private Seed company() {
		return tx.execute(s -> {
			UUID owner = newUser();
			UUID member = newUser();
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Own', now(), now()) RETURNING id",
					UUID.class);
			jdbc.update("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) VALUES "
					+ "(?, ?, 'OWNER', 'ACTIVE', now(), now()), (?, ?, 'ADMIN', 'ACTIVE', now(), now())", id, owner, id,
					member);
			return new Seed(id, owner, member);
		});
	}

	private static final String INSERT = "INSERT INTO ownership_transfer (company_id, initiated_by, recipient_id, status, "
			+ "resolution, created_at, expires_at, updated_at, resolved_at, resolved_by) "
			+ "VALUES (?, ?, ?, ?, ?, now(), now() + interval '7 days', now(), ?, ?)";

	private void insert(UUID companyId, UUID initiatedBy, UUID recipient, String status, String resolution, boolean resolved,
			UUID resolvedBy) {
		jdbc.update(INSERT, companyId, initiatedBy, recipient, status, resolution,
				resolved ? Timestamp.from(Instant.now()) : null, resolvedBy);
	}

	private void insertPending(Seed seed) {
		insert(seed.companyId(), seed.owner(), seed.member(), "PENDING", null, false, null);
	}

	// ---- the pending transfer is unique (AC-OWN-10)

	@Test
	void aPendingTransferIsValid() {
		Seed seed = company();

		insertPending(seed);

		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ? AND status = 'PENDING'",
				seed.companyId())).isEqualTo(1);
	}

	@Test
	void aSecondPendingTransferForTheSameCompanyIsRefusedButAnotherCompanyOrAFinishedOneIsFine() {
		Seed seed = company();
		insertPending(seed);

		assertThatThrownBy(() -> insertPending(seed)).isInstanceOf(DataIntegrityViolationException.class);

		insertPending(company());
		jdbc.update("UPDATE ownership_transfer SET status = 'CANCELLED', resolution = 'cancelled_by_initiator', "
				+ "resolved_at = now(), resolved_by = initiated_by WHERE company_id = ?", seed.companyId());
		insertPending(seed);
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", seed.companyId())).isEqualTo(2);
	}

	@Test
	void simultaneousInitiationsLeaveExactlyOnePendingTransfer() throws Exception {
		Seed seed = company();
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> {
				try {
					insertPending(seed);
					return "inserted";
				}
				catch (DataIntegrityViolationException e) {
					return "refused";
				}
			});
		}

		List<String> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter("inserted"::equals).count()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ? AND status = 'PENDING'",
				seed.companyId())).isEqualTo(1);
	}

	// ---- the rows cannot say impossible things

	@Test
	void ownershipCannotBeHandedToOneself() {
		Seed seed = company();
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.owner(), "PENDING", null, false, null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anExpiryNotAfterTheCreationIsRefused() {
		Seed seed = company();
		assertThatThrownBy(() -> jdbc.update("INSERT INTO ownership_transfer (company_id, initiated_by, recipient_id, status, "
				+ "created_at, expires_at, updated_at) VALUES (?, ?, ?, 'PENDING', now(), now() - interval '1 day', now())",
				seed.companyId(), seed.owner(), seed.member())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anUnknownStatusOrResolutionIsRefused() {
		Seed seed = company();
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "DONE", null, false, null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "because", true, seed.owner()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aPendingTransferCannotBeResolvedAndAFinishedOneMustBe() {
		Seed seed = company();
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "PENDING", "accepted", false, null))
			.as("pending with a resolution").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "PENDING", null, true, null))
			.as("pending with a resolution time").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "PENDING", null, false, seed.owner()))
			.as("pending with a resolver").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "COMPLETED", "accepted", false, seed.member()))
			.as("completed with no resolution time").isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theWayATransferEndedMustMatchItsStatus() {
		Seed seed = company();
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "COMPLETED", "expired", true, null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "EXPIRED", "accepted", true, seed.member()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "accepted", true, seed.member()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void onlyTheRecipientAcceptsAndOnlyTheInitiatorCancelsAndNobodyExpiresIt() {
		Seed seed = company();
		UUID other = newUser();
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "COMPLETED", "accepted", true, other))
			.as("accepted by someone else").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "cancelled_by_initiator", true, other))
			.as("cancelled by someone else").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "EXPIRED", "expired", true, seed.owner()))
			.as("expired by a person").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "cancelled_operator_transfer", true, seed.owner()))
			.as("operator cancellation by a person").isInstanceOf(DataIntegrityViolationException.class);

		insert(seed.companyId(), seed.owner(), seed.member(), "COMPLETED", "accepted", true, seed.member());
		insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "cancelled_by_initiator", true, seed.owner());
		insert(seed.companyId(), seed.owner(), seed.member(), "EXPIRED", "expired", true, null);
		insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "cancelled_operator_transfer", true, null);
		insert(seed.companyId(), seed.owner(), seed.member(), "CANCELLED", "cancelled_company_deletion", true, seed.owner());
		assertThat(count("SELECT count(*) FROM ownership_transfer WHERE company_id = ?", seed.companyId())).isEqualTo(5);
	}

	@Test
	void everyReferenceMustExist() {
		Seed seed = company();
		assertThatThrownBy(() -> insert(UUID.randomUUID(), seed.owner(), seed.member(), "PENDING", null, false, null))
			.as("company").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), UUID.randomUUID(), seed.member(), "PENDING", null, false, null))
			.as("initiator").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(seed.companyId(), seed.owner(), UUID.randomUUID(), "PENDING", null, false, null))
			.as("recipient").isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- the entity and the repository

	@Test
	void anInitiatedTransferIsPendingForSevenDaysAndRoundTripsThroughTheRepository() {
		Seed seed = company();
		Instant now = Instant.now();

		OwnershipTransfer saved = transfers.saveAndFlush(OwnershipTransfer.initiate(seed.companyId(), seed.owner(), seed.member(), now));

		OwnershipTransfer loaded = transfers.findByCompanyIdAndId(seed.companyId(), saved.getId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(TransferStatus.PENDING);
		assertThat(loaded.getResolution()).isNull();
		assertThat(loaded.getExpiresAt()).isEqualTo(loaded.getCreatedAt().plus(OwnershipTransfer.LIFETIME));
		assertThat(transfers.findByCompanyIdAndStatus(seed.companyId(), TransferStatus.PENDING)).isPresent();
	}

	@Test
	void everyWayToEndATransferWritesAValidRowAndAFinishedTransferCannotChangeAgain() {
		Seed seed = company();
		Instant now = Instant.now();
		UUID deleter = seed.owner();
		List<java.util.function.Consumer<OwnershipTransfer>> endings = List.of(t -> t.complete(now),
				t -> t.cancelByInitiator(now), t -> t.cancelForDeletion(deleter, now), t -> t.expire(now));
		List<TransferResolution> expected = List.of(TransferResolution.ACCEPTED, TransferResolution.CANCELLED_BY_INITIATOR,
				TransferResolution.CANCELLED_COMPANY_DELETION, TransferResolution.EXPIRED);

		for (int i = 0; i < endings.size(); i++) {
			OwnershipTransfer transfer = transfers
				.saveAndFlush(OwnershipTransfer.initiate(seed.companyId(), seed.owner(), seed.member(), now));
			endings.get(i).accept(transfer);
			transfers.saveAndFlush(transfer);

			OwnershipTransfer reloaded = transfers.findByCompanyIdAndId(seed.companyId(), transfer.getId()).orElseThrow();
			assertThat(reloaded.getResolution()).isEqualTo(expected.get(i));
			assertThat(reloaded.getStatus()).isEqualTo(expected.get(i).status());
			assertThat(reloaded.getResolvedAt()).isNotNull();
			assertThatThrownBy(() -> reloaded.complete(now)).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> reloaded.cancelByInitiator(now)).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> reloaded.expire(now)).isInstanceOf(IllegalStateException.class);
		}
		assertThat(transfers.findByCompanyIdAndStatus(seed.companyId(), TransferStatus.PENDING)).isEmpty();
	}

	@Test
	void aTransferIsOverdueExactlyAtItsExpiryAndReadsAsExpiredBeforeTheSweep() {
		Seed seed = company();
		Instant created = Instant.parse("2026-10-01T10:00:00Z");
		OwnershipTransfer transfer = OwnershipTransfer.initiate(seed.companyId(), seed.owner(), seed.member(), created);
		Instant expiry = created.plus(OwnershipTransfer.LIFETIME);

		assertThat(transfer.isOverdue(expiry.minusMillis(1))).isFalse();
		assertThat(transfer.effectiveStatus(expiry.minusMillis(1))).isEqualTo(TransferStatus.PENDING);
		assertThat(transfer.isOverdue(expiry)).as("not valid at the expiry itself (AC-OWN-14)").isTrue();
		assertThat(transfer.effectiveStatus(expiry)).isEqualTo(TransferStatus.EXPIRED);
		assertThat(transfer.getStatus()).as("reading did not change it").isEqualTo(TransferStatus.PENDING);

		transfer.complete(created);
		assertThat(transfer.isOverdue(expiry.plusSeconds(1))).as("a finished transfer is never overdue").isFalse();
		assertThat(transfer.effectiveStatus(expiry.plusSeconds(1))).isEqualTo(TransferStatus.COMPLETED);
	}

	@Test
	void aTransferOfAnotherCompanyIsNotFoundByIdOrByLock() {
		Seed mine = company();
		Seed theirs = company();
		OwnershipTransfer foreign = transfers.saveAndFlush(
				OwnershipTransfer.initiate(theirs.companyId(), theirs.owner(), theirs.member(), Instant.now()));

		assertThat(transfers.findByCompanyIdAndId(mine.companyId(), foreign.getId())).isEmpty();
		assertThatThrownBy(() -> tx.execute(s -> {
			companyLocks.lockCompany(mine.companyId());
			return transferLocks.lockTransfer(mine.companyId(), foreign.getId());
		})).isInstanceOf(CompanyNotFoundException.class);
		UUID locked = tx.execute(s -> {
			companyLocks.lockCompany(theirs.companyId());
			return transferLocks.lockTransfer(theirs.companyId(), foreign.getId()).getId();
		});
		assertThat(locked).isEqualTo(foreign.getId());
	}

	// ---- one Owner at every committed point (AC-OWN-05) while ownership changes hands

	private String ownerOf(UUID companyId) {
		return jdbc.queryForObject("SELECT string_agg(user_id::text, ',') FROM membership WHERE company_id = ? "
				+ "AND role = 'OWNER' AND status IN ('ACTIVE', 'SUSPENDED')", String.class, companyId);
	}

	@Test
	void demotingTheOldOwnerThenPromotingTheRecipientInOneTransactionLeavesExactlyOneOwner() {
		Seed seed = company();

		tx.executeWithoutResult(s -> {
			jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ? AND user_id = ?", seed.companyId(), seed.owner());
			jdbc.update("UPDATE membership SET role = 'OWNER' WHERE company_id = ? AND user_id = ?", seed.companyId(), seed.member());
		});

		assertThat(ownerOf(seed.companyId())).isEqualTo(seed.member().toString());
		assertThat(count("SELECT count(*) FROM membership WHERE company_id = ? AND user_id = ? AND role = 'ADMIN'",
				seed.companyId(), seed.owner())).isEqualTo(1);
	}

	@Test
	void promotingTheRecipientBeforeDemotingTheOldOwnerIsRefusedByTheDatabase() {
		Seed seed = company();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			jdbc.update("UPDATE membership SET role = 'OWNER' WHERE company_id = ? AND user_id = ?", seed.companyId(), seed.member());
			jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ? AND user_id = ?", seed.companyId(), seed.owner());
		})).isInstanceOf(DataIntegrityViolationException.class);

		assertThat(ownerOf(seed.companyId())).as("nothing changed").isEqualTo(seed.owner().toString());
	}

	@Test
	void aDemotionWithoutAPromotionCannotCommitBecauseTheCompanyWouldHaveNoOwner() {
		Seed seed = company();

		assertThatThrownBy(() -> tx.executeWithoutResult(
				s -> jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE company_id = ? AND user_id = ?", seed.companyId(), seed.owner())))
			.isInstanceOf(Exception.class);

		assertThat(ownerOf(seed.companyId())).isEqualTo(seed.owner().toString());
	}

}
