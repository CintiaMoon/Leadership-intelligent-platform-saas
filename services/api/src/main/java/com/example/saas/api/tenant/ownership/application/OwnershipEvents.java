package com.example.saas.api.tenant.ownership.application;

/** The outbox event types of ownership transfers (D-185) and the addressees of their emails. */
public final class OwnershipEvents {

	public static final String INITIATED = "OWNERSHIP_TRANSFER_INITIATED";

	public static final String COMPLETED = "OWNERSHIP_TRANSFER_COMPLETED";

	public static final String CANCELLED = "OWNERSHIP_TRANSFER_CANCELLED";

	public static final String EXPIRED = "OWNERSHIP_TRANSFER_EXPIRED";

	/** Payload value: the person who started the transfer. */
	public static final String INITIATOR = "INITIATOR";

	/** Payload value: the person who was asked to become the Owner. */
	public static final String RECIPIENT = "RECIPIENT";

	private OwnershipEvents() {
	}

}
