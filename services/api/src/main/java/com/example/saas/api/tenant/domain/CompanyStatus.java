package com.example.saas.api.tenant.domain;

/** Company lifecycle (D-106). This slice creates only ACTIVE companies; slice 007 owns the transitions. */
public enum CompanyStatus {

	ACTIVE, PENDING_DELETION

}
