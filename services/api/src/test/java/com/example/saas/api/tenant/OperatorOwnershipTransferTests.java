package com.example.saas.api.tenant;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.auth.email.EmailMessage;
import com.example.saas.api.auth.outbox.OutboxEvent;
import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.CompanyLocks;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.ownership.application.OperatorTransferEmails;
import com.example.saas.api.tenant.ownership.application.OwnershipConflictException;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import com.example.saas.api.tenant.ownership.application.TransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-OWN-18 and D-142: the operator's ownership transfer of a flagged company, run from the runbook's own SQL against
 * PostgreSQL after the disable SQL has flagged the company. Every safety condition of the script has a test that fails
 * when the condition is removed (the mutation checks are recorded in the traceability file).
 */
class OperatorOwnershipTransferTests extends AuthIntegrationTest {

	private static final Path DISABLE = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"disable-account-and-flag-companies.sql");

	private static final Path TRANSFER = Path.of("..", "..", "docs", "runbooks", "operations", "sql",
			"operator-ownership-transfer.sql");

	private static final int TRANSFER_STATEMENTS = 18;

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private CompanyLocks locks;

	@Autowired
	private OwnershipTransferService transfers;

	@Autowired
	private InvitationService invitations;

	@Autowired
	private OperatorTransferEmails handler;

	private record Person(UUID userId, String email, String name) {
	}

	private record Team(UUID companyId, Person owner, Person admin, Person otherAdmin, Person agent, UUID ownerSeat,
			UUID adminSeat, UUID otherAdminSeat, UUID agentSeat) {
	}

	private Person person(String name) {
		String email = "member." + UUID.randomUUID() + "@example.com";
		UUID id = jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, password_hash, "
				+ "email_verified_at, created_at, updated_at) VALUES (?, ?, ?, 'hash', now(), now(), now()) RETURNING id",
				UUID.class, email, email, name);
		return new Person(id, email, name);
	}

	private UUID seat(UUID companyId, Person person, String role) {
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'ACTIVE', now(), now()) RETURNING id", UUID.class, companyId, person.userId(), role);
	}

	private Team team() {
		return tx.execute(s -> {
			Person owner = person("Olga Oldowner");
			Person admin = person("Ada Newowner");
			Person otherAdmin = person("Bea Otheradmin");
			Person agent = person("Cal Agentperson");
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Handover Ltd', now(), now()) RETURNING id",
					UUID.class);
			return new Team(id, owner, admin, otherAdmin, agent, seat(id, owner, "OWNER"), seat(id, admin, "ADMIN"),
					seat(id, otherAdmin, "ADMIN"), seat(id, agent, "AGENT"));
		});
	}

	private AuthenticatedUser session(Person person) {
		Instant now = Instant.now();
		return new AuthenticatedUser(person.userId(), now, now.plus(Duration.ofHours(12)));
	}

	private void runStatements(Path script, Map<String, String> variables, int expected) throws IOException {
		List<String> statements = RunbookSql.statements(script, variables);
		assertThat(statements).as("the statements of %s", script.getFileName()).hasSize(expected);
		tx.executeWithoutResult(s -> statements.forEach(jdbc::execute));
	}

	/** The operator disables the Owner's account: the company is flagged and the Owner suspended. */
	private void disable(Person owner) throws IOException {
		runStatements(DISABLE, Map.of("email", owner.email(), "ticket", "SEC-1"), 8);
	}

	private Map<String, String> variables(Team team, Person recipient, String ticket, String nonAdminOk) {
		return Map.of("email", team.owner().email(), "recipient", recipient.email(), "company", team.companyId().toString(),
				"ticket", ticket, "non_admin_ok", nonAdminOk);
	}

	private void transfer(Team team, Person recipient) throws IOException {
		runStatements(TRANSFER, variables(team, recipient, "SEC-2", "no"), TRANSFER_STATEMENTS);
	}

	private String seatState(UUID seat) {
		return jdbc.queryForObject("SELECT role || ':' || status FROM membership WHERE id = ?", String.class, seat);
	}

	private boolean flagged(UUID companyId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT ownership_resolution_at IS NOT NULL FROM company WHERE id = ?", Boolean.class, companyId));
	}

	private int liveOwners(UUID companyId) {
		return count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
				+ "AND status IN ('ACTIVE', 'SUSPENDED')", companyId);
	}

	/** Everything the script may change for one company, as one string. */
	private String snapshot(Team team) {
		return String.join("|",
				jdbc.queryForList("SELECT role || ':' || status FROM membership WHERE company_id = ? ORDER BY id", String.class,
						team.companyId()).toString(),
				String.valueOf(flagged(team.companyId())),
				String.valueOf(count("SELECT count(*) FROM audit_event WHERE company_id = ?", team.companyId())),
				String.valueOf(count("SELECT count(*) FROM outbox_event WHERE payload->>'companyId' = ?",
						team.companyId().toString())),
				jdbc.queryForList("SELECT status FROM ownership_transfer WHERE company_id = ?", String.class, team.companyId())
					.toString(),
				jdbc.queryForList("SELECT status FROM invitation WHERE company_id = ?", String.class, team.companyId())
					.toString());
	}

	@Test
	void theTransferMakesTheRecipientTheOwnerAndTheOldOwnerASuspendedAdminInOneStep() throws Exception {
		Team team = team();
		TransferView pending = transfers.initiate(session(team.owner()), team.companyId(), team.agentSeat());
		UUID invitation = invitations.create(session(team.owner()), team.companyId(), "late.hire@example.com", Role.AGENT).id();
		UUID kept = jdbc.queryForObject("INSERT INTO invitation (company_id, email_normalized, email_original, role, "
				+ "invited_by, expires_at, created_at, updated_at) VALUES (?, 'by.admin@example.com', 'by.admin@example.com', "
				+ "'AGENT', ?, now() + interval '3 days', now(), now()) RETURNING id", UUID.class, team.companyId(),
				team.admin().userId());
		disable(team.owner());
		assertThat(flagged(team.companyId())).isTrue();

		transfer(team, team.admin());

		assertThat(seatState(team.adminSeat())).isEqualTo("OWNER:ACTIVE");
		assertThat(seatState(team.ownerSeat())).as("the old Owner is a suspended Admin").isEqualTo("ADMIN:SUSPENDED");
		assertThat(seatState(team.otherAdminSeat())).isEqualTo("ADMIN:ACTIVE");
		assertThat(seatState(team.agentSeat())).isEqualTo("AGENT:ACTIVE");
		assertThat(liveOwners(team.companyId())).isEqualTo(1);
		assertThat(flagged(team.companyId())).isFalse();
		assertThat(jdbc.queryForObject("SELECT status || ':' || resolution FROM ownership_transfer WHERE id = ?",
				String.class, pending.id())).isEqualTo("CANCELLED:cancelled_operator_transfer");
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitation))
			.as("what the old Owner left pending is revoked").isEqualTo("REVOKED");
		assertThat(jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, kept))
			.as("somebody else's invitation is not touched").isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, team.owner().userId()))
			.as("the account stays disabled").isEqualTo("DISABLED");
	}

	@Test
	void theTransferIsAuditedWithTheTicketTheDatabaseRoleAndNoPersonalData() throws Exception {
		Team team = team();
		transfers.initiate(session(team.owner()), team.companyId(), team.agentSeat());
		disable(team.owner());

		transfer(team, team.admin());

		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_type = 'operator' "
				+ "AND event_type = 'ownership.transfer.operator_completed' AND outcome = 'success' "
				+ "AND reason_code LIKE 'operator_ownership_transfer ticket=SEC-2 db_user=%'", team.companyId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND actor_type = 'operator' "
				+ "AND event_type = 'company.ownership_resolution.cleared' AND reason_code LIKE '%ticket=SEC-2'",
				team.companyId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type = 'membership.role.changed' "
				+ "AND target_id = ? AND before_value = 'OWNER' AND after_value = 'ADMIN' AND reason_code LIKE '%ticket=SEC-2'",
				team.companyId(), team.ownerSeat())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type = 'membership.role.changed' "
				+ "AND target_id = ? AND before_value = 'ADMIN' AND after_value = 'OWNER' AND reason_code LIKE '%ticket=SEC-2'",
				team.companyId(), team.adminSeat())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type = 'ownership.transfer.cancelled' "
				+ "AND before_value = 'PENDING' AND after_value = 'CANCELLED' AND reason_code LIKE '%ticket=SEC-2'",
				team.companyId())).isEqualTo(1);
		for (Person person : List.of(team.owner(), team.admin(), team.otherAdmin(), team.agent())) {
			assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND (reason_code LIKE ? OR reason_code LIKE ?)",
					team.companyId(), "%" + person.email() + "%", "%" + person.name() + "%")).isZero();
		}
	}

	@Test
	void theNewOwnerAndTheAdminsAreToldAndTheOldOwnerIsNot() throws Exception {
		Team team = team();
		disable(team.owner());
		drainOutbox();
		mail.clear();

		transfer(team, team.admin());

		List<Map<String, Object>> queued = jdbc.queryForList("SELECT payload::text AS payload, status FROM outbox_event "
				+ "WHERE event_type = 'OWNERSHIP_TRANSFER_OPERATOR_COMPLETED' AND payload->>'companyId' = ?",
				team.companyId().toString());
		assertThat(queued).hasSize(2);
		assertThat(queued).allSatisfy(row -> assertThat((String) row.get("payload"))
			.as("identifiers only").doesNotContain("@").doesNotContain("SEC-2").doesNotContain("Handover"));
		drainOutbox();
		assertThat(mail.sentTo(team.admin().email())).hasSize(1);
		assertThat(mail.sentTo(team.admin().email()).get(0).body()).contains("you are now the Owner");
		assertThat(mail.sentTo(team.otherAdmin().email())).hasSize(1);
		assertThat(mail.sentTo(team.otherAdmin().email()).get(0).body()).contains("the Owner of the company")
			.contains("was changed");
		assertThat(mail.sentTo(team.owner().email())).as("never the old Owner's address").isEmpty();
		assertThat(mail.sentTo(team.agent().email())).as("not an Agent").isEmpty();
		for (EmailMessage message : mail.sent()) {
			assertThat(message.subject()).isEqualTo("Ownership of a company was changed");
			assertThat(message.body()).doesNotContain("SEC-2").doesNotContain(team.owner().name())
				.doesNotContain(team.admin().name()).doesNotContain(team.owner().email());
		}
	}

	@Test
	void theSenderChecksTheAddresseeAgainWhenItSendsEvenForAForgedEvent() throws Exception {
		Team team = team();
		disable(team.owner());
		transfer(team, team.admin());
		drainOutbox();
		mail.clear();

		assertThat(handle(team.companyId(), team.owner(), "NEW_OWNER")).as("the old Owner").isEmpty();
		assertThat(handle(team.companyId(), team.owner(), "ADMIN")).as("the old Owner as an Admin").isEmpty();
		assertThat(handle(team.companyId(), team.agent(), "ADMIN")).as("an Agent").isEmpty();
		assertThat(handle(team.companyId(), team.otherAdmin(), "NEW_OWNER")).as("an Admin as the new Owner").isEmpty();
		assertThat(handle(team.companyId(), team.admin(), "NEW_OWNER")).isPresent();
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.otherAdmin().userId());
		assertThat(handle(team.companyId(), team.otherAdmin(), "ADMIN")).as("a disabled account").isEmpty();
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.adminSeat());
		assertThat(handle(team.companyId(), team.admin(), "NEW_OWNER")).as("a suspended member").isEmpty();
		assertThat(handle(UUID.randomUUID(), team.admin(), "ADMIN")).as("another company").isEmpty();
	}

	private Optional<EmailMessage> handle(UUID companyId, Person person, String addressee) {
		return handler.handle(new OutboxEvent(UUID.randomUUID(), OperatorTransferEmails.EVENT_TYPE,
				Map.of("companyId", companyId.toString(), "userId", person.userId().toString(), "addressee", addressee), 1, 8));
	}

	@Test
	void aPendingTransferCannotBeAcceptedAfterwardsAndTheNewOwnerCanTransferAgain() throws Exception {
		Team team = team();
		TransferView pending = transfers.initiate(session(team.owner()), team.companyId(), team.agentSeat());
		disable(team.owner());
		transfer(team, team.admin());

		assertThatThrownBy(() -> transfers.accept(session(team.agent()), team.companyId(), pending.id()))
			.isInstanceOf(OwnershipConflictException.class);

		assertThat(transfers.initiate(session(team.admin()), team.companyId(), team.otherAdminSeat()).id()).isNotNull();
	}

	@Test
	void anAgentOrAViewerCanBeTheRecipientOnlyWhenTheTicketNamedThemAndTheOperatorSaysSo() throws Exception {
		Team team = team();
		disable(team.owner());
		String before = snapshot(team);

		assertGuardStops(variables(team, team.agent(), "SEC-2", "no"), "an Agent without non_admin_ok");
		assertThat(snapshot(team)).isEqualTo(before);

		runStatements(TRANSFER, variables(team, team.agent(), "SEC-2", "yes"), TRANSFER_STATEMENTS);

		assertThat(seatState(team.agentSeat())).isEqualTo("OWNER:ACTIVE");
		assertThat(seatState(team.ownerSeat())).isEqualTo("ADMIN:SUSPENDED");
		assertThat(liveOwners(team.companyId())).isEqualTo(1);
	}

	/** Refused by one of the script's own guards (a division by zero), not by a later statement or a constraint. */
	private void assertGuardStops(Map<String, String> variables, String why) {
		assertThatThrownBy(() -> runStatements(TRANSFER, variables, TRANSFER_STATEMENTS)).as(why)
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("division by zero");
	}

	private void assertRefused(Team team, Map<String, String> variables, String why) {
		String before = snapshot(team);
		assertGuardStops(variables, why);
		assertThat(snapshot(team)).as("%s: nothing changed", why).isEqualTo(before);
	}

	@Test
	void aCompanyThatIsNotFlaggedIsRefused() throws Exception {
		Team team = team();
		// The Owner's membership is suspended and the account disabled by hand, but the company is not flagged.
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.ownerSeat());
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.owner().userId());

		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "not flagged");
	}

	@Test
	void anOwnerWhoIsStillActiveIsRefused() throws Exception {
		Team team = team();
		jdbc.update("UPDATE company SET ownership_resolution_at = now() WHERE id = ?", team.companyId());

		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "the Owner is active and enabled");
	}

	@Test
	void anOwnerWhoseAccountWasEnabledAgainIsRefused() throws Exception {
		Team team = team();
		disable(team.owner());
		jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", team.owner().userId());

		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "the account is no longer disabled");
	}

	@Test
	void anAddressThatIsNotTheCompanysOwnerIsRefused() throws Exception {
		Team team = team();
		disable(team.owner());

		assertRefused(team, Map.of("email", team.otherAdmin().email(), "recipient", team.admin().email(), "company",
				team.companyId().toString(), "ticket", "SEC-2", "non_admin_ok", "no"), "an Admin named as the old Owner");
		assertRefused(team, Map.of("email", "nobody." + UUID.randomUUID() + "@example.com", "recipient",
				team.admin().email(), "company", team.companyId().toString(), "ticket", "SEC-2", "non_admin_ok", "no"),
				"an unknown address");
	}

	@Test
	void anOwnerWhoseMembershipIsStillActiveIsRefusedEvenWithADisabledAccount() throws Exception {
		Team team = team();
		jdbc.update("UPDATE company SET ownership_resolution_at = now() WHERE id = ?", team.companyId());
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.owner().userId());

		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "the Owner's membership was never suspended");
	}

	@Test
	void membersWhoCannotReceiveMailAreNotQueuedNotices() throws Exception {
		Team team = team();
		Person third = tx.execute(s -> {
			Person p = person("Dee Thirdadmin");
			seat(team.companyId(), p, "ADMIN");
			return p;
		});
		Person fourth = tx.execute(s -> {
			Person p = person("Eve Fourthadmin");
			seat(team.companyId(), p, "ADMIN");
			return p;
		});
		disable(team.owner());
		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", third.userId());
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE company_id = ? AND user_id = ?", team.companyId(),
				fourth.userId());

		transfer(team, team.admin());

		List<String> queued = jdbc.queryForList("SELECT payload->>'userId' FROM outbox_event WHERE event_type = "
				+ "'OWNERSHIP_TRANSFER_OPERATOR_COMPLETED' AND payload->>'companyId' = ?", String.class,
				team.companyId().toString());
		assertThat(queued).containsExactlyInAnyOrder(team.admin().userId().toString(), team.otherAdmin().userId().toString());
	}

	@Test
	void aRecipientWhoIsNotAnActiveMemberOfThisCompanyIsRefused() throws Exception {
		Team team = team();
		Person stranger = person("Stan Stranger");
		Team elsewhere = team();
		disable(team.owner());

		assertRefused(team, variables(team, stranger, "SEC-2", "no"), "not a member of any company");
		assertRefused(team, variables(team, elsewhere.admin(), "SEC-2", "no"), "an Admin of another company");
		assertRefused(team, variables(team, team.owner(), "SEC-2", "no"), "the old Owner");
		assertRefused(team, variables(team, new Person(UUID.randomUUID(), "nobody." + UUID.randomUUID() + "@example.com", "X"),
				"SEC-2", "no"), "an unknown address");
		jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.adminSeat());
		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "a suspended member");
		jdbc.update("UPDATE membership SET status = 'REMOVED' WHERE id = ?", team.adminSeat());
		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "a removed member");
	}

	@Test
	void aRecipientWithoutAUsableAccountIsRefused() throws Exception {
		Team team = team();
		disable(team.owner());

		jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.admin().userId());
		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "a disabled account");
		jdbc.update("UPDATE app_user SET status = 'ACTIVE', email_verified_at = NULL WHERE id = ?", team.admin().userId());
		assertRefused(team, variables(team, team.admin(), "SEC-2", "no"), "an unverified address");
	}

	@Test
	void aBadTicketOrFlagOrCompanyIdStopsTheScript() throws Exception {
		Team team = team();
		disable(team.owner());

		for (String ticket : new String[] { "", "has space", "x".repeat(41), "a;b" }) {
			assertRefused(team, variables(team, team.admin(), ticket, "no"), "ticket '" + ticket + "'");
		}
		for (String flag : new String[] { "", "maybe", "YES", "true" }) {
			assertRefused(team, variables(team, team.admin(), "SEC-2", flag), "non_admin_ok '" + flag + "'");
		}
		for (String company : new String[] { "", "not-a-uuid", team.companyId().toString().toUpperCase(),
				UUID.randomUUID().toString() }) {
			assertRefused(team, Map.of("email", team.owner().email(), "recipient", team.admin().email(), "company", company,
					"ticket", "SEC-2", "non_admin_ok", "no"), "company '" + company + "'");
		}
	}

	@Test
	void aSecondRunStopsBecauseTheFlagIsGone() throws Exception {
		Team team = team();
		disable(team.owner());
		transfer(team, team.admin());
		String after = snapshot(team);

		assertGuardStops(variables(team, team.otherAdmin(), "SEC-3", "no"), "the flag is gone");

		assertThat(snapshot(team)).isEqualTo(after);
		assertThat(seatState(team.adminSeat())).isEqualTo("OWNER:ACTIVE");
	}

	@Test
	void aCompanyWithNoOneToTransferToStaysFlagged() throws Exception {
		Person owner = person("Solo Owner");
		UUID company = tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"INSERT INTO company (name, created_at, updated_at) VALUES ('Solo', now(), now()) RETURNING id", UUID.class);
			seat(id, owner, "OWNER");
			return id;
		});
		runStatements(DISABLE, Map.of("email", owner.email(), "ticket", "SEC-1"), 8);

		assertGuardStops(Map.of("email", owner.email(), "recipient", owner.email(), "company", company.toString(),
				"ticket", "SEC-2", "non_admin_ok", "yes"), "nobody to transfer to");

		assertThat(flagged(company)).isTrue();
		assertThat(liveOwners(company)).isEqualTo(1);
	}

	@Test
	void theScriptWaitsForTheCompanyLockAndThenReReadsTheRecipient() throws Exception {
		Team team = team();
		disable(team.owner());
		List<String> statements = RunbookSql.statements(TRANSFER, variables(team, team.admin(), "SEC-2", "no"));

		CompletableFuture<Void> script = new CompletableFuture<>();
		tx.executeWithoutResult(s -> {
			locks.lockCompany(team.companyId());
			CompletableFuture.runAsync(() -> {
				try {
					tx.executeWithoutResult(t -> statements.forEach(jdbc::execute));
					script.complete(null);
				}
				catch (RuntimeException e) {
					script.completeExceptionally(e);
				}
			});
			sleep(1500);
			assertThat(script.isDone()).as("the script waits for the company lock").isFalse();
			jdbc.update("UPDATE membership SET status = 'SUSPENDED' WHERE id = ?", team.adminSeat());
		});

		assertThatThrownBy(() -> script.get(20, java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(DataAccessException.class);
		assertThat(flagged(team.companyId())).isTrue();
		assertThat(seatState(team.ownerSeat())).isEqualTo("OWNER:SUSPENDED");
		assertThat(liveOwners(team.companyId())).isEqualTo(1);
	}

	@Test
	void theScriptWaitsForARecipientAccountThatIsBeingChanged() throws Exception {
		Team team = team();
		disable(team.owner());
		List<String> statements = RunbookSql.statements(TRANSFER, variables(team, team.admin(), "SEC-2", "no"));

		CompletableFuture<Void> script = new CompletableFuture<>();
		tx.executeWithoutResult(s -> {
			jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", team.admin().userId());
			CompletableFuture.runAsync(() -> {
				try {
					tx.executeWithoutResult(t -> statements.forEach(jdbc::execute));
					script.complete(null);
				}
				catch (RuntimeException e) {
					script.completeExceptionally(e);
				}
			});
			sleep(1500);
			assertThat(script.isDone()).as("the script waits for the recipient's account").isFalse();
			jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", team.admin().userId());
		});

		assertThatThrownBy(() -> script.get(20, java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(DataAccessException.class);
		assertThat(flagged(team.companyId())).isTrue();
		assertThat(seatState(team.adminSeat())).isEqualTo("ADMIN:ACTIVE");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
