package com.example.saas.api.tenant.deletion.web;

import java.time.Instant;

import com.example.saas.api.tenant.deletion.application.DeletionView;
import com.example.saas.api.tenant.domain.CompanyStatus;

/** The company's status after a request or a cancellation and, while the deletion is pending, its two instants. */
record DeletionResponse(CompanyStatus status, Instant requestedAt, Instant purgeAt) {

	static DeletionResponse of(DeletionView view) {
		return new DeletionResponse(view.status(), view.requestedAt(), view.purgeAt());
	}

}
