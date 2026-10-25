package com.example.saas.api.tenant.ownership.domain;

/**
 * How a transfer ended, stored as the lower-case code the migration checks (V14). The code, not the enum name, is what
 * the database holds, so the converter in the entity maps both ways.
 */
public enum TransferResolution {

	ACCEPTED("accepted", TransferStatus.COMPLETED), CANCELLED_BY_INITIATOR("cancelled_by_initiator", TransferStatus.CANCELLED),
	CANCELLED_COMPANY_DELETION("cancelled_company_deletion", TransferStatus.CANCELLED),
	CANCELLED_OPERATOR_TRANSFER("cancelled_operator_transfer", TransferStatus.CANCELLED),
	EXPIRED("expired", TransferStatus.EXPIRED);

	private final String code;

	private final TransferStatus status;

	TransferResolution(String code, TransferStatus status) {
		this.code = code;
		this.status = status;
	}

	public String code() {
		return code;
	}

	/** The final status a transfer has when it ended this way. */
	public TransferStatus status() {
		return status;
	}

	public static TransferResolution ofCode(String code) {
		for (TransferResolution resolution : values()) {
			if (resolution.code.equals(code)) {
				return resolution;
			}
		}
		throw new IllegalArgumentException("Unknown ownership transfer resolution: " + code);
	}

}
