package com.example.saas.api.tenant.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.RecentAuthentication;
import com.example.saas.api.tenant.domain.MembershipOperation;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import com.example.saas.api.tenant.persistence.MemberRow;
import com.example.saas.api.tenant.persistence.Membership;
import com.example.saas.api.tenant.persistence.MembershipRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership changes (SPEC-MEM). Each change is one transaction: authorize under the locks
 * ({@link MembershipChangeAuthorizer}), write, and audit, so a change either happens completely with its audit rows or
 * not at all, and the transaction commits before the API reports success. No network call or email happens inside.
 */
@Service
public class MembershipService {

	/** Until plan limits exist the list is bounded by this (D-132). */
	static final int LIST_LIMIT = 500;

	private final MembershipChangeAuthorizer authorizer;

	private final MembershipRepository memberships;

	private final UserAccountRepository users;

	private final AuditLog audit;

	private final RecentAuthentication recentAuthentication;

	private final ActiveMemberLimit memberLimit;

	private final Clock clock;

	MembershipService(MembershipChangeAuthorizer authorizer, MembershipRepository memberships,
			UserAccountRepository users, AuditLog audit, RecentAuthentication recentAuthentication,
			ActiveMemberLimit memberLimit, Clock clock) {
		this.authorizer = authorizer;
		this.memberships = memberships;
		this.users = users;
		this.audit = audit;
		this.recentAuthentication = recentAuthentication;
		this.memberLimit = memberLimit;
		this.clock = clock;
	}

	/** The ACTIVE and SUSPENDED members. The caller was already authorized for "View member list". */
	@Transactional(readOnly = true)
	public List<MemberView> list(UUID companyId, UUID callerUserId) {
		return memberships
			.listMembers(companyId, EnumSet.of(MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED),
					PageRequest.of(0, LIST_LIMIT))
			.stream()
			.map(row -> view(row, callerUserId))
			.toList();
	}

	@Transactional
	public MemberView changeRole(AuthenticatedUser actor, UUID companyId, UUID membershipId, Role newRole) {
		MembershipChangeAuthorizer.Authorized authorized = authorize(actor, companyId, membershipId,
				MembershipOperation.CHANGE_ROLE, newRole);
		Membership target = authorized.target();
		Role before = target.getRole();
		target.changeRole(newRole, clock.instant());
		audit.recordChange("membership.role.changed", AuditLog.Actor.user, actor.userId(), "membership", target.getId(),
				companyId, "success", null, before.name(), newRole.name());
		return viewOf(target, actor.userId());
	}

	@Transactional
	public MemberView suspend(AuthenticatedUser actor, UUID companyId, UUID membershipId) {
		return viewOf(applyStatus(actor, companyId, membershipId, MembershipOperation.SUSPEND,
				MembershipStatus.SUSPENDED, "membership.suspended"), actor.userId());
	}

	@Transactional
	public MemberView reactivate(AuthenticatedUser actor, UUID companyId, UUID membershipId) {
		// Called under the company lock the authorizer took, so two reactivations cannot both take the last place.
		return viewOf(applyStatus(actor, companyId, membershipId, MembershipOperation.REACTIVATE,
				MembershipStatus.ACTIVE, "membership.reactivated"), actor.userId());
	}

	@Transactional
	public void remove(AuthenticatedUser actor, UUID companyId, UUID membershipId) {
		applyStatus(actor, companyId, membershipId, MembershipOperation.REMOVE, MembershipStatus.REMOVED,
				"membership.removed");
	}

	/** The caller leaves. The membership is recorded as removed by the member (D-20, D-128). */
	@Transactional
	public void leave(AuthenticatedUser actor, UUID companyId) {
		applyStatus(actor, companyId, null, MembershipOperation.LEAVE, MembershipStatus.REMOVED, "membership.left");
	}

	private Membership applyStatus(AuthenticatedUser actor, UUID companyId, UUID membershipId,
			MembershipOperation operation, MembershipStatus newStatus, String eventType) {
		MembershipChangeAuthorizer.Authorized authorized = authorize(actor, companyId, membershipId, operation, null);
		Membership target = authorized.target();
		if (operation == MembershipOperation.REACTIVATE && !memberLimit.hasRoomForAnotherActiveMember(companyId)) {
			throw new MembershipConflictException();
		}
		MembershipStatus before = target.getStatus();
		Instant now = clock.instant();
		target.changeStatus(newStatus, actor.userId(), now);
		audit.recordChange(eventType, AuditLog.Actor.user, actor.userId(), "membership", target.getId(), companyId,
				"success", null, before.name(), newStatus.name());
		return target;
	}

	private MembershipChangeAuthorizer.Authorized authorize(AuthenticatedUser actor, UUID companyId,
			UUID membershipId, MembershipOperation operation, Role newRole) {
		MembershipChangeAuthorizer.Authorized authorized = authorizer.authorize(actor.userId(), companyId, membershipId,
				operation, newRole);
		if (authorized.decision().recentAuthenticationRequired()) {
			recentAuthentication.require(actor);
		}
		return authorized;
	}

	private MemberView viewOf(Membership membership, UUID callerUserId) {
		UserAccount user = users.findById(membership.getUserId()).orElseThrow();
		return new MemberView(membership.getId(), user.getDisplayName(), user.getEmailOriginal(), membership.getRole(),
				membership.getStatus(), membership.getCreatedAt(), membership.getUserId().equals(callerUserId));
	}

	private static MemberView view(MemberRow row, UUID callerUserId) {
		return new MemberView(row.membershipId(), row.displayName(), row.email(), row.role(), row.status(),
				row.joinedAt(), row.userId().equals(callerUserId));
	}

}
