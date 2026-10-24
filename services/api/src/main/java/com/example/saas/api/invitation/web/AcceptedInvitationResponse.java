package com.example.saas.api.invitation.web;

import java.util.UUID;

import com.example.saas.api.invitation.application.AcceptedInvitation;
import com.example.saas.api.tenant.domain.Role;

/** The company the user joined and the role received; the same shape as a company in the user's own list. */
record AcceptedInvitationResponse(UUID id, String name, Role role) {

	static AcceptedInvitationResponse of(AcceptedInvitation accepted) {
		return new AcceptedInvitationResponse(accepted.companyId(), accepted.companyName(), accepted.role());
	}

}
