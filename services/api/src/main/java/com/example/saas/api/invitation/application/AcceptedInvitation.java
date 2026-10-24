package com.example.saas.api.invitation.application;

import java.util.UUID;

import com.example.saas.api.tenant.domain.Role;

/** What a successful acceptance returns: the company that was joined and the role received. */
public record AcceptedInvitation(UUID companyId, String companyName, Role role) {
}
