package com.example.saas.api.plan.application;

import java.time.Clock;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import com.example.saas.api.plan.domain.FeatureKey;
import com.example.saas.api.plan.domain.LimitKey;
import com.example.saas.api.plan.domain.SubscriptionState;
import com.example.saas.api.plan.persistence.CompanySubscription;
import com.example.saas.api.plan.persistence.CompanySubscriptionRepository;
import com.example.saas.api.plan.persistence.Plan;
import com.example.saas.api.plan.persistence.PlanRepository;
import com.example.saas.api.plan.persistence.StateRow;
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
		return subscriptions.findStateRow(companyId)
			.map(s -> s.effectiveAt(clock.instant()))
			.orElse(SubscriptionState.NO_PLAN);
	}

	/**
	 * The plan, the effective state, the features and the limits for the plan endpoint (Q-13). Teams and assessments are
	 * listed with {@code enforced: false} and no usage until their slices exist (Q-12).
	 */
	@Transactional(readOnly = true)
	public SubscriptionView describe(UUID companyId) {
		java.time.Instant now = clock.instant();
		StateRow row = subscriptions.findStateRow(companyId).orElse(null);
		CompanySubscription subscription = subscriptions.findById(companyId).orElse(null);
		Plan plan = subscription == null || subscription.getPlanId() == null ? null
				: plans.findById(subscription.getPlanId()).orElse(null);
		SubscriptionState state = row == null ? SubscriptionState.NO_PLAN : row.effectiveAt(now);
		if (plan == null) {
			return new SubscriptionView(null, state, null, null, List.of(), List.of());
		}
		// The trial instants matter only while the subscription is a trial or one of its two expired states.
		boolean trialOnly = state != SubscriptionState.ACTIVE;
		List<FeatureKey> features = plans.findFeatureKeys(plan.getId()).stream().map(FeatureKey::valueOf).toList();
		List<SubscriptionView.LimitView> limits = plans.findLimits(plan.getId()).stream().map(r -> {
			LimitKey key = LimitKey.valueOf((String) r[0]);
			int value = ((Number) r[1]).intValue();
			boolean enforced = key == LimitKey.ACTIVE_MEMBERS;
			Integer usage = enforced
					? (int) memberships.countByCompanyIdAndStatus(companyId, MembershipStatus.ACTIVE) : null;
			return new SubscriptionView.LimitView(key, value, enforced, usage);
		}).toList();
		return new SubscriptionView(new SubscriptionView.PlanRef(plan.getCode(), plan.getName(), plan.getVersion()),
				state, trialOnly ? subscription.getTrialEndsAt() : null, trialOnly ? subscription.getReadOnlyEndsAt() : null,
				features, limits);
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
