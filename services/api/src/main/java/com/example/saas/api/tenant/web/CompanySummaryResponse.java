package com.example.saas.api.tenant.web;

import java.util.UUID;

import com.example.saas.api.tenant.application.CompanyView;
import com.example.saas.api.tenant.domain.Role;

record CompanySummaryResponse(UUID id, String name, Role role) {

	static CompanySummaryResponse of(CompanyView view) {
		return new CompanySummaryResponse(view.id(), view.name(), view.role());
	}

}
