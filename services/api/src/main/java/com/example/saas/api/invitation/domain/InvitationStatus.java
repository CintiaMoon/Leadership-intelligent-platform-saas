package com.example.saas.api.invitation.domain;

/** Where an invitation is in its life (SPEC-INV). Only PENDING can be accepted; every other state is final. */
public enum InvitationStatus {

	PENDING, ACCEPTED, REVOKED, EXPIRED

}
