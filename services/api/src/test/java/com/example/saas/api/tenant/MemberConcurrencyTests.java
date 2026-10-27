package com.example.saas.api.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.auth.AuthApi;
import com.example.saas.api.auth.AuthIntegrationTest;
import com.example.saas.api.tenant.application.CompanyLocks;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.saas.api.auth.AuthApi.STRONG_PASSWORD;
import static com.example.saas.api.auth.AuthApi.freshIp;
import static com.example.saas.api.auth.AuthApi.json;
import static com.example.saas.api.auth.AuthApi.sessionCookie;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races between membership changes, run through the real endpoints against PostgreSQL (AC-MEM-03, 08, 16). The
 * company-row lock (D-125) must make every outcome one that some serial order of the requests could produce. The lock
 * timeout is the production value (5 s) here: the shorter test default would turn a long queue into 503s.
 */
@TestPropertySource(properties = "app.tenant.lock-timeout=5s")
class MemberConcurrencyTests extends AuthIntegrationTest {

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private CompanyLocks locks;

	private record Person(UUID userId, Cookie cookie, String csrf) {
	}

	private Person loggedIn() throws Exception {
		String email = AuthApi.uniqueEmail();
		UUID userId = registerVerified(email, STRONG_PASSWORD);
		Cookie cookie = sessionCookie(api.login(email, STRONG_PASSWORD, freshIp()));
		return new Person(userId, cookie, api.sessionCsrf(cookie, freshIp()));
	}

	private UUID dbUser() {
		String email = UUID.randomUUID() + "@example.com";
		return jdbc.queryForObject("INSERT INTO app_user (email_normalized, email_original, display_name, "
				+ "password_hash, created_at, updated_at) VALUES (?, ?, 'U', 'hash', now(), now()) RETURNING id",
				UUID.class, email, email);
	}

	private UUID seat(UUID companyId, UUID userId, String role, String status) {
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now()) RETURNING id", UUID.class, companyId, userId, role, status);
	}

	/** A company whose Owner is {@code owner}; the Owner's membership ID is returned through the holder. */
	private UUID company(UUID ownerUserId, UUID[] ownerMembership) {
		return tx.execute(s -> {
			UUID id = jdbc.queryForObject(
					"WITH c AS (INSERT INTO company (name, created_at, updated_at) VALUES ('Race', now(), now()) RETURNING id), s AS (INSERT INTO company_subscription (company_id, plan_id, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at) SELECT c.id, (SELECT id FROM plan WHERE code = 'TRIAL' AND version = 1), 'TRIALING', now(), now() + interval '336 hours', now() + interval '672 hours', now(), now(), now() FROM c) SELECT id FROM c",
					UUID.class);
			ownerMembership[0] = seat(id, ownerUserId, "OWNER", "ACTIVE");
			return id;
		});
	}

	private int call(Person who, HttpMethod method, String path, String body) throws Exception {
		return api.send(method, path, body, who.cookie(), who.csrf(), freshIp()).getResponse().getStatus();
	}

	private static String member(UUID companyId, UUID membershipId) {
		return "/api/v1/companies/" + companyId + "/members/" + membershipId;
	}

	private int liveOwners(UUID companyId) {
		return count("SELECT count(*) FROM membership WHERE company_id = ? AND role = 'OWNER' "
				+ "AND status IN ('ACTIVE', 'SUSPENDED')", companyId);
	}

	private String status(UUID membershipId) {
		return jdbc.queryForObject("SELECT status FROM membership WHERE id = ?", String.class, membershipId);
	}

	private String role(UUID membershipId) {
		return jdbc.queryForObject("SELECT role FROM membership WHERE id = ?", String.class, membershipId);
	}

	private int audited(String eventType, UUID membershipId) {
		return count("SELECT count(*) FROM audit_event WHERE event_type = ? AND target_id = ?", eventType, membershipId);
	}

	@Test
	void aRoleChangeRacingARemovalOfTheSameMemberLeavesOneConsistentState() throws Exception {
		Person owner = loggedIn();
		Person admin = loggedIn();
		UUID[] ownerSeat = new UUID[1];
		UUID companyId = company(owner.userId(), ownerSeat);
		seat(companyId, admin.userId(), "ADMIN", "ACTIVE");

		for (int round = 0; round < 12; round++) {
			UUID agent = seat(companyId, dbUser(), "AGENT", "ACTIVE");
			List<Integer> results = Parallel.runAll(List.<Callable<Integer>>of(
					() -> call(owner, HttpMethod.PATCH, member(companyId, agent), json("role", "VIEWER")),
					() -> call(admin, HttpMethod.DELETE, member(companyId, agent), null)));
			int patch = results.get(0);

			assertThat(results.get(1)).as("round %d: removal always succeeds", round).isEqualTo(204);
			assertThat(patch).as("round %d: the role change either went first or was refused", round).isIn(200, 409);
			assertThat(status(agent)).isEqualTo("REMOVED");
			assertThat(role(agent)).as("the role is the new one only if the change went first")
				.isEqualTo(patch == 200 ? "VIEWER" : "AGENT");
			assertThat(audited("membership.role.changed", agent)).isEqualTo(patch == 200 ? 1 : 0);
			assertThat(audited("membership.removed", agent)).isEqualTo(1);
			assertThat(liveOwners(companyId)).isEqualTo(1);
		}
		assertThat(status(ownerSeat[0])).isEqualTo("ACTIVE");
	}

	@Test
	void parallelAttemptsOnTheOwnerByTheOwnerAndByAnAdminAllFailAndNothingChanges() throws Exception {
		Person owner = loggedIn();
		Person admin = loggedIn();
		UUID[] ownerSeat = new UUID[1];
		UUID companyId = company(owner.userId(), ownerSeat);
		seat(companyId, admin.userId(), "ADMIN", "ACTIVE");
		String target = member(companyId, ownerSeat[0]);
		String leave = "/api/v1/companies/" + companyId + "/leave";

		List<Callable<Integer>> tasks = new ArrayList<>();
		List<Integer> expected = new ArrayList<>();
		for (int i = 0; i < 7 * 4; i++) {
			switch (i % 7) {
				case 0 -> {
					tasks.add(() -> call(owner, HttpMethod.PATCH, target, json("role", "ADMIN")));
					expected.add(409);
				}
				case 1 -> {
					tasks.add(() -> call(owner, HttpMethod.POST, target + "/suspend", null));
					expected.add(409);
				}
				case 2 -> {
					tasks.add(() -> call(owner, HttpMethod.DELETE, target, null));
					expected.add(409);
				}
				case 3 -> {
					tasks.add(() -> call(admin, HttpMethod.PATCH, target, json("role", "AGENT")));
					expected.add(403);
				}
				case 4 -> {
					tasks.add(() -> call(admin, HttpMethod.POST, target + "/suspend", null));
					expected.add(403);
				}
				case 5 -> {
					tasks.add(() -> call(admin, HttpMethod.DELETE, target, null));
					expected.add(403);
				}
				default -> {
					tasks.add(() -> call(owner, HttpMethod.POST, leave, null));
					expected.add(403);
				}
			}
		}

		List<Integer> results = Parallel.runAll(tasks);

		assertThat(results).containsExactlyElementsOf(expected);
		assertThat(status(ownerSeat[0])).isEqualTo("ACTIVE");
		assertThat(role(ownerSeat[0])).isEqualTo("OWNER");
		assertThat(liveOwners(companyId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_event WHERE target_id = ? AND event_type LIKE 'membership.%'",
				ownerSeat[0])).as("nothing happened to the Owner, so nothing is audited as done").isZero();
		assertThat(jdbc.queryForObject("SELECT status_changed_at IS NULL FROM membership WHERE id = ?", Boolean.class,
				ownerSeat[0])).isTrue();
	}

	@Test
	void theSameChangeRequestedManyTimesAtOnceHappensExactlyOnce() throws Exception {
		Person owner = loggedIn();
		Person admin = loggedIn();
		UUID[] ownerSeat = new UUID[1];
		UUID companyId = company(owner.userId(), ownerSeat);
		seat(companyId, admin.userId(), "ADMIN", "ACTIVE");
		UUID agent = seat(companyId, dbUser(), "AGENT", "ACTIVE");
		String path = member(companyId, agent);
		List<Callable<Integer>> actors = new ArrayList<>();

		for (int i = 0; i < 12; i++) {
			Person who = i % 2 == 0 ? owner : admin;
			actors.add(() -> call(who, HttpMethod.POST, path + "/suspend", null));
		}
		List<Integer> suspends = Parallel.runAll(actors);
		assertThat(suspends.stream().filter(s -> s == 200).count()).as("suspend: one winner").isEqualTo(1);
		assertThat(suspends.stream().filter(s -> s == 409).count()).isEqualTo(11);
		assertThat(audited("membership.suspended", agent)).isEqualTo(1);

		actors.clear();
		for (int i = 0; i < 12; i++) {
			Person who = i % 2 == 0 ? owner : admin;
			actors.add(() -> call(who, HttpMethod.POST, path + "/reactivate", null));
		}
		List<Integer> reactivates = Parallel.runAll(actors);
		assertThat(reactivates.stream().filter(s -> s == 200).count()).as("reactivate: one winner").isEqualTo(1);
		assertThat(audited("membership.reactivated", agent)).isEqualTo(1);

		actors.clear();
		for (int i = 0; i < 12; i++) {
			Person who = i % 2 == 0 ? owner : admin;
			actors.add(() -> call(who, HttpMethod.DELETE, path, null));
		}
		List<Integer> removals = Parallel.runAll(actors);
		assertThat(removals.stream().filter(s -> s == 204).count()).as("remove: one winner").isEqualTo(1);
		assertThat(removals.stream().filter(s -> s == 409).count()).isEqualTo(11);
		assertThat(audited("membership.removed", agent)).isEqualTo(1);
		assertThat(status(agent)).isEqualTo("REMOVED");
		assertThat(liveOwners(companyId)).isEqualTo(1);
	}

	@Test
	void twoRoleChangesOfOneMemberLeaveTheRoleTheLastAuditRowSays() throws Exception {
		Person owner = loggedIn();
		Person admin = loggedIn();
		UUID[] ownerSeat = new UUID[1];
		UUID companyId = company(owner.userId(), ownerSeat);
		seat(companyId, admin.userId(), "ADMIN", "ACTIVE");

		for (int round = 0; round < 10; round++) {
			UUID agent = seat(companyId, dbUser(), "AGENT", "ACTIVE");
			List<Integer> results = Parallel.runAll(List.<Callable<Integer>>of(
					() -> call(owner, HttpMethod.PATCH, member(companyId, agent), json("role", "VIEWER")),
					() -> call(admin, HttpMethod.PATCH, member(companyId, agent), json("role", "AGENT"))));

			long successes = results.stream().filter(s -> s == 200).count();
			assertThat(results).as("round %d", round).allMatch(s -> s == 200 || s == 409);
			assertThat(successes).isGreaterThanOrEqualTo(1);
			assertThat(audited("membership.role.changed", agent)).isEqualTo((int) successes);
			assertThat(role(agent)).as("the final role is the last audited one")
				.isEqualTo(jdbc.queryForObject("SELECT after_value FROM audit_event WHERE event_type = "
						+ "'membership.role.changed' AND target_id = ? ORDER BY occurred_at DESC LIMIT 1", String.class,
						agent));
		}
	}

	@Test
	void aMemberLeavingWhileAnAdminSuspendsThemEndsInExactlyOneOfTwoStates() throws Exception {
		Person owner = loggedIn();
		UUID[] ownerSeat = new UUID[1];
		for (int round = 0; round < 8; round++) {
			Person agent = loggedIn();
			UUID companyId = company(owner.userId(), ownerSeat);
			UUID agentSeat = seat(companyId, agent.userId(), "AGENT", "ACTIVE");

			List<Integer> results = Parallel.runAll(List.<Callable<Integer>>of(
					() -> call(agent, HttpMethod.POST, "/api/v1/companies/" + companyId + "/leave", null),
					() -> call(owner, HttpMethod.POST, member(companyId, agentSeat) + "/suspend", null)));
			int leave = results.get(0);
			int suspend = results.get(1);

			boolean leftFirst = leave == 204 && suspend == 409;
			boolean suspendedFirst = suspend == 200 && leave == 404;
			assertThat(leftFirst || suspendedFirst)
				.as("round %d: leave=%d suspend=%d must match a serial order", round, leave, suspend)
				.isTrue();
			assertThat(status(agentSeat)).isEqualTo(leftFirst ? "REMOVED" : "SUSPENDED");
			assertThat(audited("membership.left", agentSeat)).isEqualTo(leftFirst ? 1 : 0);
			assertThat(audited("membership.suspended", agentSeat)).isEqualTo(suspendedFirst ? 1 : 0);
			assertThat(liveOwners(companyId)).isEqualTo(1);
		}
	}

	@Test
	void memberChangesDuringAnOwnershipTransferNeverLeaveTwoOwnersOrNone() throws Exception {
		Person oldOwner = loggedIn();
		Person successor = loggedIn();

		for (int round = 0; round < 8; round++) {
			UUID[] ownerSeat = new UUID[1];
			UUID companyId = company(oldOwner.userId(), ownerSeat);
			UUID successorSeat = seat(companyId, successor.userId(), "ADMIN", "ACTIVE");
			UUID agent = seat(companyId, dbUser(), "AGENT", "ACTIVE");

			// What slice 005 will do: lock the company row first, demote the old Owner, promote the successor, commit.
			Callable<Integer> transfer = () -> {
				tx.executeWithoutResult(s -> {
					locks.lockCompany(companyId);
					jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE id = ?", ownerSeat[0]);
					jdbc.update("UPDATE membership SET role = 'OWNER' WHERE id = ?", successorSeat);
				});
				return 0;
			};
			List<Integer> results = Parallel.runAll(List.of(transfer,
					() -> call(oldOwner, HttpMethod.POST, member(companyId, agent) + "/suspend", null),
					() -> call(successor, HttpMethod.DELETE, member(companyId, ownerSeat[0]), null),
					() -> call(successor, HttpMethod.PATCH, member(companyId, agent), json("role", "VIEWER"))));

			assertThat(results.subList(1, 4)).as("round %d", round)
				.allSatisfy(status -> assertThat(status).isIn(200, 204, 403, 404, 409, 503));
			assertThat(liveOwners(companyId)).as("round %d: exactly one Owner", round).isEqualTo(1);
			assertThat(role(successorSeat)).as("the transfer always completes").isEqualTo("OWNER");
			assertThat(role(ownerSeat[0])).isEqualTo("ADMIN");
		}
	}

	/**
	 * Many mixed changes at once from several actors, then a replay of the audit trail: each membership's final role
	 * and status must be exactly what its audited changes, applied in order, produce, and every success must have left
	 * exactly one audit row.
	 */
	@Test
	void aStormOfMixedChangesEndsInStatesThatTheAuditTrailExplains() throws Exception {
		Person owner = loggedIn();
		Person admin1 = loggedIn();
		Person admin2 = loggedIn();
		UUID[] ownerSeat = new UUID[1];
		UUID companyId = company(owner.userId(), ownerSeat);
		seat(companyId, admin1.userId(), "ADMIN", "ACTIVE");
		seat(companyId, admin2.userId(), "ADMIN", "ACTIVE");
		List<UUID> targets = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			targets.add(seat(companyId, dbUser(), i % 2 == 0 ? "AGENT" : "VIEWER", "ACTIVE"));
		}
		Map<UUID, String[]> initial = new java.util.HashMap<>();
		jdbc.query("SELECT id, role, status FROM membership WHERE company_id = ?",
				rs -> {
					initial.put(UUID.fromString(rs.getString("id")),
							new String[] { rs.getString("role"), rs.getString("status") });
				}, companyId);
		List<Person> actors = List.of(owner, admin1, admin2);
		String[] roles = { "ADMIN", "AGENT", "VIEWER" };
		Random random = new Random(20261022L);

		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 90; i++) {
			Person actor = actors.get(random.nextInt(actors.size()));
			UUID target = random.nextInt(12) == 0 ? ownerSeat[0] : targets.get(random.nextInt(targets.size()));
			String path = member(companyId, target);
			switch (random.nextInt(4)) {
				case 0 -> {
					String role = roles[random.nextInt(roles.length)];
					tasks.add(() -> call(actor, HttpMethod.PATCH, path, json("role", role)));
				}
				case 1 -> tasks.add(() -> call(actor, HttpMethod.POST, path + "/suspend", null));
				case 2 -> tasks.add(() -> call(actor, HttpMethod.POST, path + "/reactivate", null));
				default -> tasks.add(() -> call(actor, HttpMethod.DELETE, path, null));
			}
		}
		// Repeat the storm so that removals and reactivations meet every state.
		List<Integer> results = new ArrayList<>();
		for (int round = 0; round < 3; round++) {
			results.addAll(Parallel.runAll(tasks));
		}

		assertThat(results).as("no server error, no deadlock").allSatisfy(
				status -> assertThat(status).isIn(200, 204, 403, 404, 409, 503));
		long successes = results.stream().filter(s -> s == 200 || s == 204).count();
		assertThat(count("SELECT count(*) FROM audit_event WHERE company_id = ? AND event_type LIKE 'membership.%'",
				companyId)).as("one audit row per success").isEqualTo((int) successes);
		assertThat(liveOwners(companyId)).isEqualTo(1);
		assertThat(role(ownerSeat[0])).isEqualTo("OWNER");
		assertThat(status(ownerSeat[0])).isEqualTo("ACTIVE");

		Map<UUID, String[]> replay = new java.util.HashMap<>(initial);
		jdbc.query("SELECT event_type, target_id, before_value, after_value FROM audit_event WHERE company_id = ? "
				+ "AND event_type LIKE 'membership.%' ORDER BY occurred_at, id", rs -> {
					UUID id = UUID.fromString(rs.getString("target_id"));
					String[] state = replay.get(id);
					boolean roleEvent = rs.getString("event_type").equals("membership.role.changed");
					int slot = roleEvent ? 0 : 1;
					assertThat(state[slot]).as("the audited 'before' matches the state before the change")
						.isEqualTo(rs.getString("before_value"));
					state[slot] = rs.getString("after_value");
				}, companyId);
		replay.forEach((id, state) -> {
			assertThat(role(id)).as("role of %s", id).isEqualTo(state[0]);
			assertThat(status(id)).as("status of %s", id).isEqualTo(state[1]);
		});
	}

}
