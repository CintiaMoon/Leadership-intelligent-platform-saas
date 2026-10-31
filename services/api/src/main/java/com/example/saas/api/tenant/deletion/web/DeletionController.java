package com.example.saas.api.tenant.deletion.web;

import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.deletion.application.DeletionService;
import com.example.saas.api.tenant.web.PathIds;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller: parses the path, delegates to {@link DeletionService}, shapes the response (SPEC-DEL). The routes do not
 * use {@code @Authorized}: the service answers an outsider with 404 and a member who is not the Owner with 403 before any
 * lock, and decides role, state and deadline again under the company lock. The company ID in the path is a selector only,
 * and nothing in the body is read (there is no body).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/deletion")
class DeletionController {

	private final DeletionService deletion;

	DeletionController(DeletionService deletion) {
		this.deletion = deletion;
	}

	@PostMapping
	DeletionResponse request(@PathVariable String companyId, @AuthenticationPrincipal AuthenticatedUser principal) {
		return DeletionResponse.of(deletion.request(principal, PathIds.parse(companyId)));
	}

	@DeleteMapping
	DeletionResponse cancel(@PathVariable String companyId, @AuthenticationPrincipal AuthenticatedUser principal) {
		return DeletionResponse.of(deletion.cancel(principal, PathIds.parse(companyId)));
	}

}
