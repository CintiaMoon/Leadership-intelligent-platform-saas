package com.example.saas.api.invitation.web;

import com.example.saas.api.tenant.domain.Role;

/**
 * The address to invite and the role to give. Not annotated for validation on purpose: the service counts the attempt
 * against the send limits first and judges the address (at most 254 characters, enforced by the parser before any other
 * work) and the role (permission first, then the request itself) in that order. Unknown fields are rejected while
 * parsing. The role is read as an enum so an unknown value is a bad request; {@code OWNER} and a missing role are refused
 * by the rules.
 */
record CreateInvitationRequest(String email, Role role) {

	@Override
	public String toString() {
		return "CreateInvitationRequest[redacted]";
	}

}
