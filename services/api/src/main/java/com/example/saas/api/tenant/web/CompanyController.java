package com.example.saas.api.tenant.web;

import java.util.List;

import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.ClientAddress;
import com.example.saas.api.tenant.application.AuthorizedCompany;
import com.example.saas.api.tenant.application.CompanyService;
import com.example.saas.api.tenant.domain.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: parses, delegates to {@link CompanyService}, and shapes the response (SPEC-TEN). */
@RestController
@RequestMapping("/api/v1/companies")
class CompanyController {

	private final CompanyService companies;

	CompanyController(CompanyService companies) {
		this.companies = companies;
	}

	@PostMapping
	ResponseEntity<CompanySummaryResponse> create(@RequestBody CreateCompanyRequest request,
			@AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest http) {
		var created = companies.create(principal.userId(), request.name(), ClientAddress.of(http).limitKey());
		return ResponseEntity.status(HttpStatus.CREATED).body(CompanySummaryResponse.of(created));
	}

	@GetMapping
	List<CompanySummaryResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
		return companies.listFor(principal.userId()).stream().map(CompanySummaryResponse::of).toList();
	}

	@GetMapping("/{companyId}")
	CompanyDetailsResponse get(@Authorized(Permission.VIEW_COMPANY_DETAILS) AuthorizedCompany company) {
		return CompanyDetailsResponse.of(companies.get(company));
	}

}
