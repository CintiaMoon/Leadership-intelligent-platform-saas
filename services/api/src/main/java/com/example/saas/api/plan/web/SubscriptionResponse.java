package com.example.saas.api.plan.web;

import java.time.Instant;
import java.util.List;

import com.example.saas.api.plan.application.SubscriptionView;
import com.example.saas.api.plan.domain.FeatureKey;
import com.example.saas.api.plan.domain.LimitKey;
import com.example.saas.api.plan.domain.SubscriptionState;

record SubscriptionResponse(PlanResponse plan, SubscriptionState state, Instant trialEndsAt, Instant readOnlyEndsAt,
		List<FeatureKey> features, List<LimitResponse> limits) {

	record PlanResponse(String code, String name, int version) {
	}

	record LimitResponse(LimitKey key, int value, boolean enforced, Integer usage) {
	}

	static SubscriptionResponse of(SubscriptionView view) {
		PlanResponse plan = view.plan() == null ? null
				: new PlanResponse(view.plan().code(), view.plan().name(), view.plan().version());
		return new SubscriptionResponse(plan, view.state(), view.trialEndsAt(), view.readOnlyEndsAt(), view.features(),
				view.limits().stream().map(l -> new LimitResponse(l.key(), l.value(), l.enforced(), l.usage())).toList());
	}

}
