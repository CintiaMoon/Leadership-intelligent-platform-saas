package com.example.saas.api.tenant.application;

/**
 * The caller has no ACTIVE membership in the company, or the company does not exist. One exception for both so the
 * response cannot reveal that a company exists (D-108).
 */
public class CompanyNotFoundException extends RuntimeException {

	public CompanyNotFoundException() {
		super(null, null, false, false);
	}

}
