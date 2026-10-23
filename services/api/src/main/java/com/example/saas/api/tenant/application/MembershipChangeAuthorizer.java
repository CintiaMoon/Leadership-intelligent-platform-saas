package com.example.saas.api.tenant.application;

import java.util.List;
import java.util.UUID;

import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.tenant.domain.ChangeDecision;
import com.example.saas.api.tenant.domain.MembershipChanges;
import com.example.saas.api.tenant.domain.MembershipOperation;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.Company;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step one of every membership change (D-124, D-125): authorize inside the caller's transaction, under the locks, using
 * the values as they are now. The caller then writes the change and the audit rows in the same transaction.
 *
 * <p>
 * Sequence: (1) an unlocked check that the caller is an ACTIVE member, so an outsider can never queue for a company
 * lock; (2) lock the company row; (3) lock the target membership (the caller's own, for LEAVE); (4) load the caller's
 * membership again, now that the locks serialize every change in this company; (5) decide with
 * {@link MembershipChanges}.
 */
@Component
public class MembershipChangeAuthorizer {

	/** The locked rows and the verdict, for the caller to act on. */
	public record Authorized(Company company, Membership actor, Membership target, ChangeDecision decision) {
	}

	private final MembershipRepository memberships;

	private final CompanyLocks locks;

	MembershipChangeAuthorizer(MembershipRepository memberships, CompanyLocks locks) {
		this.memberships = memberships;
		this.locks = locks;
	}

	/**
	 * @param membershipId the membership to change; ignored for LEAVE, which always concerns the caller's own
	 * @param newRole the requested role for CHANGE_ROLE
	 * @throws CompanyNotFoundException the caller is not an ACTIVE member, or the membership is not in this company (404)
	 * @throws PermissionDeniedException the caller's role does not allow it (403; the handler audits it)
	 * @throws MembershipConflictException not possible in the current state, or it would touch the Owner (409)
	 * @throws ValidationFailedException the request asks for something no actor may do, such as assigning Owner (400)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Authorized authorize(UUID userId, UUID companyId, UUID membershipId, MembershipOperation operation,
			Role newRole) {
		// Counts rows without loading an entity: an entity loaded here would be reused after the locks with stale state.
		if (!memberships.existsByCompanyIdAndUserIdAndStatus(companyId, userId, MembershipStatus.ACTIVE)) {
			throw new CompanyNotFoundException();
		}
		Company company = locks.lockCompany(companyId);
		Membership actor;
		Membership target;
		if (operation == MembershipOperation.LEAVE) {
			actor = memberships.findByCompanyIdAndUserIdAndStatus(companyId, userId, MembershipStatus.ACTIVE)
				.orElseThrow(CompanyNotFoundException::new);
			target = locks.lockMembership(companyId, actor.getId());
		}
		else {
			target = locks.lockMembership(companyId, membershipId);
			actor = memberships.findByCompanyIdAndUserIdAndStatus(companyId, userId, MembershipStatus.ACTIVE)
				.orElseThrow(CompanyNotFoundException::new);
		}
		ChangeDecision decision = MembershipChanges.decide(operation, actor.getRole(), target.getRole(),
				target.getStatus(), actor.getId().equals(target.getId()), newRole, company.getStatus());
		switch (decision.outcome()) {
			case FORBIDDEN -> {
				// Audited by the exception handler once this transaction has ended (see PermissionDeniedException).
				throw new PermissionDeniedException(userId, companyId, decision.deniedPermission());
			}
			case CONFLICT -> throw new MembershipConflictException();
			case INVALID -> throw new ValidationFailedException(List.of(new FieldProblem("role", "INVALID")));
			case ALLOWED -> {
			}
		}
		return new Authorized(company, actor, target, decision);
	}

}
