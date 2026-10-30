package com.example.saas.api.tenant.domain;

/**
 * Company lifecycle (D-106, SPEC-DEL). A company is created ACTIVE; a deletion request moves it to PENDING_DELETION and
 * the purge worker (slice 007, phase 4) through the three purge states. Everything but ACTIVE is closed to ordinary
 * operations ({@code CompanyPolicy}), so a new value fails closed.
 */
public enum CompanyStatus {

	ACTIVE, PENDING_DELETION, PURGE_QUEUED, PURGING, PURGE_FAILED

}
