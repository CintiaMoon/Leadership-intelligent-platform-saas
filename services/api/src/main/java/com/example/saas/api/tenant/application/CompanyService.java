package com.example.saas.api.tenant.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.saas.api.auth.application.AuthRateLimits;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.tenant.domain.CompanyName;
import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company creation and reading (SPEC-TEN). The caller's identity comes from the session; the role is decided here and
 * by {@link CompanyAuthorizationService}, never taken from a request.
 */
@Service
public class CompanyService {

	private final RateLimiter rateLimiter;

	private final CompanyCreator creator;

	private final CompanyRepository companies;

	private final MembershipRepository memberships;

	private final Clock clock;

	CompanyService(RateLimiter rateLimiter, CompanyCreator creator, CompanyRepository companies,
			MembershipRepository memberships, Clock clock) {
		this.rateLimiter = rateLimiter;
		this.creator = creator;
		this.companies = companies;
		this.memberships = memberships;
		this.clock = clock;
	}

	/**
	 * Both limits count the attempt before the name is validated (AC-TEN-13, 15).
	 *
	 * @param clientKey the rate-limit key of the client address
	 * @throws com.example.saas.api.ratelimit.RateLimitedException a limit was exceeded (429)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 * @throws ValidationFailedException the name breaks the rule (400)
	 */
	public CompanyView create(UUID userId, String rawName, String clientKey) {
		AuthRateLimits.requireWithin(rateLimiter.hit(CompanyRateLimits.CREATE_IP, clientKey), clock);
		AuthRateLimits.requireWithin(rateLimiter.hit(CompanyRateLimits.CREATE_ACCOUNT, userId.toString()), clock);
		String name = CompanyName.normalize(rawName)
			.orElseThrow(() -> new ValidationFailedException(List.of(new FieldProblem("name", "INVALID_NAME"))));
		return creator.create(userId, name);
	}

	/** The caller's ACTIVE memberships. A company pending deletion is listed only where the policy lets the role read. */
	@Transactional(readOnly = true)
	public List<CompanyView> listFor(UUID userId) {
		List<Membership> mine = memberships.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE);
		Map<UUID, Company> byId = companies.findAllById(mine.stream().map(Membership::getCompanyId).toList())
			.stream()
			.collect(Collectors.toMap(Company::getId, Function.identity()));
		return mine.stream()
			.filter(m -> byId.containsKey(m.getCompanyId()))
			.filter(m -> CompanyPolicy.permits(m.getRole(), Permission.VIEW_COMPANY_DETAILS,
					byId.get(m.getCompanyId()).getStatus(), null))
			.map(m -> view(byId.get(m.getCompanyId()), m))
			.sorted(Comparator.comparing(CompanyView::name).thenComparing(CompanyView::id))
			.toList();
	}

	/** The company the caller was already authorized for. */
	@Transactional(readOnly = true)
	public CompanyView get(AuthorizedCompany authorized) {
		Company company = companies.findById(authorized.companyId()).orElseThrow(CompanyNotFoundException::new);
		return new CompanyView(company.getId(), company.getName(), company.getStatus(), authorized.role(),
				company.getCreatedAt());
	}

	private static CompanyView view(Company company, Membership membership) {
		return new CompanyView(company.getId(), company.getName(), company.getStatus(), membership.getRole(),
				company.getCreatedAt());
	}

}
