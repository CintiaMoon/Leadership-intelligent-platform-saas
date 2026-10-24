package com.example.saas.api.tenant.application;

import java.time.Duration;
import java.util.UUID;

import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lock order for membership changes (D-125): the company row first, then the target membership, always inside the
 * caller's transaction. Because every change locks the company row first, changes in one company run one at a time and
 * cannot deadlock each other; slice 005 uses the same order (D-32). A lock that is not granted within the timeout
 * fails with a locking exception, which the API reports as 503 with {@code Retry-After}; nothing has changed then.
 */
@Component
public class CompanyLocks {

	private final CompanyRepository companies;

	private final MembershipRepository memberships;

	private final EntityManager entityManager;

	private final Duration timeout;

	CompanyLocks(CompanyRepository companies, MembershipRepository memberships, EntityManager entityManager,
			@Value("${app.tenant.lock-timeout:5s}") Duration timeout) {
		this.companies = companies;
		this.memberships = memberships;
		this.entityManager = entityManager;
		this.timeout = timeout;
	}

	/** Locks the company row. Must be the first lock taken in the transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Company lockCompany(UUID companyId) {
		limitWait();
		return companies.lockById(companyId).orElseThrow(CompanyNotFoundException::new);
	}

	/**
	 * Locks one membership of the company. A membership of another company is reported as missing, exactly like an
	 * unknown one, so a path cannot reach across tenants (AC-TEN-08).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Membership lockMembership(UUID companyId, UUID membershipId) {
		limitWait();
		return memberships.lockByCompanyIdAndId(companyId, membershipId).orElseThrow(CompanyNotFoundException::new);
	}

	/**
	 * {@code SET LOCAL lock_timeout}: lasts for this transaction only, so no pooled connection keeps it. Public so that
	 * other locks taken in the same transaction (invitations) wait no longer than the company lock does.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void limitWait() {
		entityManager.createNativeQuery("select set_config('lock_timeout', :value, true)")
			.setParameter("value", timeout.toMillis() + "ms")
			.getSingleResult();
	}

}
