package com.example.saas.api.tenant.application;

import java.util.Optional;
import java.util.UUID;

import com.example.saas.api.tenant.domain.CompanyPolicy;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.CompanyRepository;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Service;

/**
 * The single enforcement point for tenant access (D-109). The company ID from the request is only a selector: access
 * comes from an ACTIVE membership loaded from the database on every call, so a role change, suspension or removal
 * applies to the next request even while the session stays valid.
 */
@Service
public class CompanyAuthorizationService {

	private final MembershipRepository memberships;

	private final CompanyRepository companies;

	private final DenialAudit denialAudit;

	CompanyAuthorizationService(MembershipRepository memberships, CompanyRepository companies,
			DenialAudit denialAudit) {
		this.memberships = memberships;
		this.companies = companies;
		this.denialAudit = denialAudit;
	}

	/** For permissions that act on no other membership. */
	public AuthorizedCompany require(UUID userId, UUID companyId, Permission permission) {
		return require(userId, companyId, permission, null);
	}

	/**
	 * @param targetRole the role of the membership being acted on, for permissions that depend on it
	 * @throws CompanyNotFoundException if the caller has no ACTIVE membership, or the company does not exist
	 * @throws PermissionDeniedException if the caller is an ACTIVE member whose role does not hold the permission
	 */
	public AuthorizedCompany require(UUID userId, UUID companyId, Permission permission, Role targetRole) {
		Optional<Membership> membership = memberships.findByCompanyIdAndUserIdAndStatus(companyId, userId,
				MembershipStatus.ACTIVE);
		Optional<Company> company = membership.flatMap(m -> companies.findById(m.getCompanyId()));
		if (company.isEmpty()) {
			// Not audited: a flood of probes for other tenants would drown the trail (D-115).
			throw new CompanyNotFoundException();
		}
		Role role = membership.get().getRole();
		if (!CompanyPolicy.permits(role, permission, company.get().getStatus(), targetRole)) {
			denialAudit.denied(userId, companyId, permission);
			throw new PermissionDeniedException();
		}
		return new AuthorizedCompany(companyId, userId, role);
	}

}
