package com.example.saas.api.tenant.ownership.web;

import java.util.List;
import java.util.UUID;

import com.example.saas.api.auth.web.SessionManager;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.tenant.ownership.application.OwnershipTransferService;
import com.example.saas.api.tenant.web.PathIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller: parses the path, delegates to {@link OwnershipTransferService}, shapes the response (SPEC-OWN). The
 * routes do not use {@code @Authorized}: whether the caller may act depends on who they are in the transfer (the Owner who
 * initiated it, the member who was named), so the service decides, before any lock for a caller who can never do the
 * operation and under the locks for the rest. The company and transfer IDs in the path are selectors only.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
class OwnershipTransferController {

	private final OwnershipTransferService transfers;

	private final SessionManager sessions;

	OwnershipTransferController(OwnershipTransferService transfers, SessionManager sessions) {
		this.transfers = transfers;
		this.sessions = sessions;
	}

	@PostMapping("/ownership-transfers")
	ResponseEntity<OwnershipTransferResponse> initiate(@PathVariable String companyId,
			@RequestBody InitiateTransferRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
		UUID recipient = recipientMembershipId(request);
		var created = transfers.initiate(principal, PathIds.parse(companyId), recipient);
		return ResponseEntity.status(HttpStatus.CREATED).body(OwnershipTransferResponse.of(created));
	}

	@GetMapping("/ownership-transfer")
	OwnershipTransferResponse pending(@PathVariable String companyId, @AuthenticationPrincipal AuthenticatedUser principal) {
		return OwnershipTransferResponse.of(transfers.current(principal, PathIds.parse(companyId)));
	}

	@GetMapping("/ownership-transfers/{transferId}")
	OwnershipTransferResponse get(@PathVariable String companyId, @PathVariable String transferId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return OwnershipTransferResponse
			.of(transfers.get(principal, PathIds.parse(companyId), PathIds.parse(transferId)));
	}

	@PostMapping("/ownership-transfers/{transferId}/accept")
	OwnershipTransferResponse accept(@PathVariable String companyId, @PathVariable String transferId,
			@AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest http) {
		var completed = transfers.accept(principal, PathIds.parse(companyId), PathIds.parse(transferId));
		// The change committed. A change to one's own role rotates the session identifier (AC-AUTH-23).
		sessions.rotate(http);
		return OwnershipTransferResponse.of(completed);
	}

	@PostMapping("/ownership-transfers/{transferId}/cancel")
	OwnershipTransferResponse cancel(@PathVariable String companyId, @PathVariable String transferId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return OwnershipTransferResponse
			.of(transfers.cancel(principal, PathIds.parse(companyId), PathIds.parse(transferId)));
	}

	/** A missing or malformed ID in the body is a bad request; whether it names a membership of this company is the service's answer. */
	private static UUID recipientMembershipId(InitiateTransferRequest request) {
		String raw = request == null ? null : request.recipientMembershipId();
		if (raw == null || raw.isBlank()) {
			throw new ValidationFailedException(List.of(new FieldProblem("recipientMembershipId", "REQUIRED")));
		}
		if (!PathIds.isCanonical(raw)) {
			throw new ValidationFailedException(List.of(new FieldProblem("recipientMembershipId", "INVALID")));
		}
		return UUID.fromString(raw);
	}

}
