package com.example.saas.api.invitation.web;

import java.util.List;

import com.example.saas.api.invitation.application.InvitationService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.domain.Permission;
import com.example.saas.api.tenant.web.Authorized;
import com.example.saas.api.tenant.web.PathIds;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller: parses the path, delegates to {@link InvitationService}, shapes the response (SPEC-INV). The write
 * routes do not use {@code @Authorized}: whether the caller may act depends on the role being invited, so the service
 * decides, before any lock for a caller who can never invite and under the locks for the rest. The company and
 * invitation IDs in the path are selectors only.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
class InvitationController {

	private final InvitationService invitations;

	InvitationController(InvitationService invitations) {
		this.invitations = invitations;
	}

	@PostMapping("/invitations")
	ResponseEntity<InvitationResponse> create(@PathVariable String companyId,
			@RequestBody CreateInvitationRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
		var created = invitations.create(principal, PathIds.parse(companyId), request.email(), request.role());
		return ResponseEntity.status(HttpStatus.CREATED).body(InvitationResponse.of(created));
	}

	@GetMapping("/invitations")
	List<InvitationResponse> list(@Authorized(Permission.VIEW_MEMBER_LIST) AuthorizedCompany company) {
		return invitations.listPending(company.companyId()).stream().map(InvitationResponse::of).toList();
	}

	@PostMapping("/invitations/{invitationId}/resend")
	InvitationResponse resend(@PathVariable String companyId, @PathVariable String invitationId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return InvitationResponse
			.of(invitations.resend(principal, PathIds.parse(companyId), PathIds.parse(invitationId)));
	}

	@DeleteMapping("/invitations/{invitationId}")
	ResponseEntity<Void> revoke(@PathVariable String companyId, @PathVariable String invitationId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		invitations.revoke(principal, PathIds.parse(companyId), PathIds.parse(invitationId));
		return ResponseEntity.noContent().build();
	}

}
