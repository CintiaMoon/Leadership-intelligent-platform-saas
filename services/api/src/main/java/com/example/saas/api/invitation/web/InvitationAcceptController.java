package com.example.saas.api.invitation.web;

import com.example.saas.api.invitation.application.InvitationAcceptService;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller for accepting an invitation. There is no company in the path: the company comes from the invitation
 * the token identifies, so the request cannot reach into another company (AC-INV-06). The caller must have a valid,
 * verified session and the session-bound CSRF token; the signed-in address, not anything in the request, is matched
 * against the invitation (AC-INV-02).
 */
@RestController
@RequestMapping("/api/v1/invitations")
class InvitationAcceptController {

	private final InvitationAcceptService accepting;

	InvitationAcceptController(InvitationAcceptService accepting) {
		this.accepting = accepting;
	}

	@PostMapping("/accept")
	AcceptedInvitationResponse accept(@RequestBody AcceptInvitationRequest request,
			@AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest http) {
		return AcceptedInvitationResponse.of(
				accepting.accept(principal.userId(), request.token(), ClientAddress.of(http).limitKey()));
	}

}
