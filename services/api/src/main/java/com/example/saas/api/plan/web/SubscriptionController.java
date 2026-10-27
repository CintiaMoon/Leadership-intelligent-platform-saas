package com.example.saas.api.plan.web;

import com.example.saas.api.plan.application.EntitlementService;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.web.Authorized;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller for the company's plan (SPEC-PLAN, Q-13). Read only: no request can change a plan, so there is no
 * write route. The role (Owner and Admin) and the subscription state are decided before this method runs.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/subscription")
class SubscriptionController {

	private final EntitlementService entitlements;

	SubscriptionController(EntitlementService entitlements) {
		this.entitlements = entitlements;
	}

	@GetMapping
	SubscriptionResponse get(@Authorized(Permission.VIEW_PLAN) AuthorizedCompany company) {
		return SubscriptionResponse.of(entitlements.describe(company.companyId()));
	}

}
