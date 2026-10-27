package com.example.saas.api.plan.application;

import java.time.Clock;
import java.util.OptionalInt;
import java.util.UUID;

import com.example.saas.api.plan.domain.FeatureKey;
import com.example.saas.api.plan.domain.LimitKey;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.plan.persistence.CompanySubscription;
import com.example.saas.api.plan.persistence.CompanySubscriptionRepository;
import com.example.saas.api.plan.persistence.PlanRepository;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one reader of the plan tables (Q-23). Every answer comes from the server's subscription row and the injected
 * clock, never from anything the client sent (AC-PLAN-03). The gating of operations by state (Q-08) is added to
 * {@code CompanyPolicy} in the next phase; this class answers the state, the features and the limits.
 *
 * <p>
 * A company with no subscription row cannot exist after V15 (company creation writes one). If one is ever missing, it
 * is treated as NO_PLAN, which has no features and no limits, so it fails closed.
 */
@Service
public class EntitlementService {

	private final CompanySubscriptionRepository subscriptions;

	private final PlanRepository plans;

	private final MembershipRepository memberships;

	private final Clock clock;

	EntitlementService(CompanySubscriptionRepository subscriptions, PlanRepository plans,
			MembershipRepository memberships, Clock clock) {
		this.subscriptions = subscriptions;
		this.plans = plans;
		this.memberships = memberships;
		this.clock = clock;
	}

	/** The effective state now (AC-PLAN-10); reading never writes. */
	@Transactional(readOnly = true)
	public SubscriptionState state(UUID companyId) {
		return subscriptions.findById(companyId)
			.map(s -> s.effectiveAt(clock.instant()))
			.orElse(SubscriptionState.NO_PLAN);
	}

	/** Whether the company's plan version includes the feature; false without a plan. */
	@Transactional(readOnly = true)
	public boolean hasFeature(UUID companyId, FeatureKey feature) {
		return subscriptions.findById(companyId)
			.map(CompanySubscription::getPlanId)
			.map(planId -> plans.hasFeature(planId, feature.name()))
			.orElse(false);
	}

	/** The limit of the company's plan version; empty without a plan. */
	@Transactional(readOnly = true)
	public OptionalInt limit(UUID companyId, LimitKey key) {
		return subscriptions.findById(companyId)
			.map(CompanySubscription::getPlanId)
			.flatMap(planId -> plans.findLimit(planId, key.name()))
			.map(OptionalInt::of)
			.orElse(OptionalInt.empty());
	}

	/**
	 * Whether the company may add one more of this kind. Only members are counted in this slice; teams and assessments
	 * are added by the slices that define them. Without a plan there is nothing to count against, so the answer is
	 * false: the gating of Q-08 refuses those operations before this is asked, and this stays closed if it is not.
	 * The caller holds the company lock, so the count and the decision cannot interleave with another change.
	 */
	@Transactional(readOnly = true)
	public boolean hasRoomFor(UUID companyId, LimitKey key) {
		if (key != LimitKey.ACTIVE_MEMBERS) {
			throw new IllegalArgumentException("No usage is counted yet for " + key);
		}
		OptionalInt limit = limit(companyId, key);
		return limit.isPresent()
				&& memberships.countByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE) < limit.getAsInt();
	}

}
