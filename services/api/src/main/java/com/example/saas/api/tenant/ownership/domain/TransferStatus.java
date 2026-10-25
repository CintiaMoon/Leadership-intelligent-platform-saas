package com.example.saas.api.tenant.ownership.domain;

/** Where an ownership transfer is in its life (SPEC-OWN). Only PENDING can change; every other state is final. */
public enum TransferStatus {

	PENDING, COMPLETED, CANCELLED, EXPIRED

}
