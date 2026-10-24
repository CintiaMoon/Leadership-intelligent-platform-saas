package com.example.saas.api.invitation.web;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.invitation.application.InvitationView;
import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.tenant.domain.Role;

/** An invitation as the API returns it. It has no field for a token or its hash. */
record InvitationResponse(UUID id, String email, Role role, InvitationStatus status, Instant expiresAt,
		Instant createdAt, boolean emailSent) {

	static InvitationResponse of(InvitationView view) {
		return new InvitationResponse(view.id(), view.email(), view.role(), view.status(), view.expiresAt(),
				view.createdAt(), view.emailSent());
	}

}
