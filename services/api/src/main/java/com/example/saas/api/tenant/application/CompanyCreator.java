package com.example.saas.api.tenant.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.plan.application.SubscriptionService;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transaction that creates a company (AC-TEN-01, 02): the company, the Owner membership, the subscription (a
 * Trial for the user's first company, AC-PLAN-09) and the audit rows commit together or not at all. The database also
 * refuses a company without an Owner at commit (D-112). No network
 * call or email happens inside it.
 */
@Component
class CompanyCreator {

	private final CompanyRepository companies;

	private final MembershipRepository memberships;

	private final AuditLog audit;

	private final SubscriptionService subscriptions;

	private final Clock clock;

	CompanyCreator(CompanyRepository companies, MembershipRepository memberships, AuditLog audit,
			SubscriptionService subscriptionService, Clock clock) {
		this.companies = companies;
		this.memberships = memberships;
		this.audit = audit;
		this.subscriptions = subscriptionService;
		this.clock = clock;
	}

	@Transactional
	CompanyView create(UUID userId, String name) {
		Instant now = clock.instant();
		Company company = companies.saveAndFlush(Company.create(name, now));
		Membership owner = memberships.save(Membership.activate(company.getId(), userId, Role.OWNER, now));
		audit.recordForCompany("tenant.company.created", AuditLog.Actor.user, userId, "company", company.getId(),
				company.getId(), "success", null);
		audit.recordForCompany("tenant.membership.created", AuditLog.Actor.user, userId, "membership", owner.getId(),
				company.getId(), "success", null);
		subscriptions.startFor(userId, company.getId(), now);
		return new CompanyView(company.getId(), company.getName(), company.getStatus(), Role.OWNER,
				company.getCreatedAt());
	}

}
