package com.example.saas.api.tenant.web;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.application.MemberView;
import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;

record MemberResponse(UUID id, String displayName, String email, Role role, MembershipStatus status, Instant joinedAt,
		boolean self) {

	static MemberResponse of(MemberView view) {
		return new MemberResponse(view.id(), view.displayName(), view.email(), view.role(), view.status(),
				view.joinedAt(), view.self());
	}

}
