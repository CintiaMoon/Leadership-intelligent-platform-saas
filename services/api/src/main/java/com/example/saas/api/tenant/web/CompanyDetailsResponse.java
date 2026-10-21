package com.example.saas.api.tenant.web;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.application.CompanyView;
import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.Role;

record CompanyDetailsResponse(UUID id, String name, CompanyStatus status, Role role, Instant createdAt) {

	static CompanyDetailsResponse of(CompanyView view) {
		return new CompanyDetailsResponse(view.id(), view.name(), view.status(), view.role(), view.createdAt());
	}

}
