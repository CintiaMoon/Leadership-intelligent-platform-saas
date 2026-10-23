package com.example.saas.api.tenant.web;

import java.util.List;

import com.example.saas.api.auth.web.SessionManager;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.application.MembershipService;
import com.example.saas.api.tenant.domain.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller: parses the path, delegates to {@link MembershipService}, shapes the response (SPEC-MEM). The write
 * routes do not use {@code @Authorized}: whether the caller may act depends on the target's role, so the service
 * decides under the locks. The company and membership IDs in the path are selectors only.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
class MemberController {

	private final MembershipService memberships;

	private final SessionManager sessions;

	MemberController(MembershipService memberships, SessionManager sessions) {
		this.memberships = memberships;
		this.sessions = sessions;
	}

	@GetMapping("/members")
	List<MemberResponse> list(@Authorized(Permission.VIEW_MEMBER_LIST) AuthorizedCompany company) {
		return memberships.list(company.companyId(), company.userId()).stream().map(MemberResponse::of).toList();
	}

	@PatchMapping("/members/{membershipId}")
	MemberResponse changeRole(@PathVariable String companyId, @PathVariable String membershipId,
			@RequestBody ChangeMemberRoleRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
		return MemberResponse.of(memberships.changeRole(principal, PathIds.parse(companyId),
				PathIds.parse(membershipId), request.role()));
	}

	@PostMapping("/members/{membershipId}/suspend")
	MemberResponse suspend(@PathVariable String companyId, @PathVariable String membershipId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return MemberResponse
			.of(memberships.suspend(principal, PathIds.parse(companyId), PathIds.parse(membershipId)));
	}

	@PostMapping("/members/{membershipId}/reactivate")
	MemberResponse reactivate(@PathVariable String companyId, @PathVariable String membershipId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return MemberResponse
			.of(memberships.reactivate(principal, PathIds.parse(companyId), PathIds.parse(membershipId)));
	}

	@DeleteMapping("/members/{membershipId}")
	ResponseEntity<Void> remove(@PathVariable String companyId, @PathVariable String membershipId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		memberships.remove(principal, PathIds.parse(companyId), PathIds.parse(membershipId));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/leave")
	ResponseEntity<Void> leave(@PathVariable String companyId, @AuthenticationPrincipal AuthenticatedUser principal,
			HttpServletRequest http) {
		memberships.leave(principal, PathIds.parse(companyId));
		// The change committed. A change to one's own membership rotates the session identifier (AC-AUTH-23).
		sessions.rotate(http);
		return ResponseEntity.noContent().build();
	}

}
