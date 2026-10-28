package com.example.saas.api.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.saas.api.tenant.Parallel;
import com.example.saas.api.tenant.application.CompanyView;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.tenant.application.ActiveMemberLimit;
import com.example.saas.api.tenant.application.MemberLimitReachedException;
import com.example.saas.api.tenant.application.PlanRestrictedException;
import com.example.saas.api.tenant.application.MembershipService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plan member limit through reactivation, the operation that adds an ACTIVE member (Q-11; AC-PLAN-05, AC-MEM-17),
 * against PostgreSQL. The Trial allows 15 active members.
 */
class PlanActiveMemberLimitTests extends PlanTestBase {

	@Autowired
	private MembershipService memberships;

	@Autowired
	private ActiveMemberLimit limit;

	private UUID member(UUID companyId, String status) {
		UUID user = newUser();
		return jdbc.queryForObject("INSERT INTO membership (company_id, user_id, role, status, created_at, updated_at) "
				+ "VALUES (?, ?, 'AGENT', ?, now(), now()) RETURNING id", UUID.class, companyId, user, status);
	}

	private int active(UUID companyId) {
		return count("SELECT count(*) FROM membership WHERE company_id = ? AND status = 'ACTIVE'", companyId);
	}

	/** A trial company (limit 15) with the Owner and {@code others - 1} more ACTIVE members. */
	private Fixture companyWithActive(int total) {
		UUID owner = newUser();
		CompanyView company = createCompany(owner);
		for (int i = 1; i < total; i++) {
			member(company.id(), "ACTIVE");
		}
		return new Fixture(company.id(), owner);
	}

	private record Fixture(UUID companyId, UUID owner) {
	}

	@Test
	void aReactivationUpToTheLimitIsAllowedAndOneBeyondItIsRefused() {
		Fixture f = companyWithActive(14);
		UUID first = member(f.companyId(), "SUSPENDED");
		UUID second = member(f.companyId(), "SUSPENDED");

		memberships.reactivate(session(f.owner()), f.companyId(), first);
		assertThat(active(f.companyId())).isEqualTo(15);

		assertThatThrownBy(() -> memberships.reactivate(session(f.owner()), f.companyId(), second))
			.isInstanceOf(MemberLimitReachedException.class);
		assertThat(active(f.companyId())).isEqualTo(15);
	}

	@Test
	void suspendedMembersAreNotCounted() {
		Fixture f = companyWithActive(10);
		for (int i = 0; i < 20; i++) {
			member(f.companyId(), "SUSPENDED");
		}
		UUID target = member(f.companyId(), "SUSPENDED");

		memberships.reactivate(session(f.owner()), f.companyId(), target);

		assertThat(active(f.companyId())).isEqualTo(11);
	}

	@Test
	void eightSimultaneousReactivationsAtLimitMinusOneAdmitExactlyOne() throws Exception {
		Fixture f = companyWithActive(14);
		List<UUID> suspended = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			suspended.add(member(f.companyId(), "SUSPENDED"));
		}
		List<Callable<String>> tasks = new ArrayList<>();
		for (UUID seat : suspended) {
			tasks.add(() -> {
				try {
					memberships.reactivate(session(f.owner()), f.companyId(), seat);
					return "ok";
				}
				catch (MemberLimitReachedException e) {
					return "full";
				}
			});
		}

		List<String> results = Parallel.runAll(tasks);

		assertThat(results.stream().filter("ok"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter("full"::equals).count()).isEqualTo(7);
		assertThat(active(f.companyId())).isEqualTo(15);
	}

	@Test
	void aCompanyWithoutAPlanHasNoRoomAndTheGatingRefusesBeforeTheLimitIsAsked() {
		UUID user = newUser();
		createCompany(user);
		CompanyView noPlan = createCompany(user);
		UUID target = member(noPlan.id(), "SUSPENDED");

		// The limit itself fails closed: with no plan there is no room...
		assertThat(limit.hasRoomForAnotherActiveMember(noPlan.id())).isFalse();
		// ...but reactivation is refused earlier, by the subscription state (403 PLAN_RESTRICTED), so nobody meets this.
		assertThatThrownBy(() -> memberships.reactivate(session(user), noPlan.id(), target))
			.isInstanceOfSatisfying(PlanRestrictedException.class,
					e -> assertThat(e.state()).isEqualTo(SubscriptionState.NO_PLAN));
		assertThat(active(noPlan.id())).isEqualTo(1);
	}

	@Test
	void anotherCompanysMembersDoNotCountTowardsTheLimit() {
		Fixture crowded = companyWithActive(15);
		Fixture quiet = companyWithActive(1);
		UUID target = member(quiet.companyId(), "SUSPENDED");

		memberships.reactivate(session(quiet.owner()), quiet.companyId(), target);

		assertThat(active(quiet.companyId())).isEqualTo(2);
		assertThat(active(crowded.companyId())).isEqualTo(15);
	}

}
