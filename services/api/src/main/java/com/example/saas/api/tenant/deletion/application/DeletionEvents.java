package com.example.saas.api.tenant.deletion.application;

/** The outbox event types and the audit event types of company deletion (plan Q-09, Q-27). */
public final class DeletionEvents {

	/** To the Owner and the Admins: a deletion was requested, with the date of the purge. */
	public static final String REQUESTED = "COMPANY_DELETION_REQUESTED";

	/** To the Owner and the Admins: the deletion was cancelled. */
	public static final String CANCELLED = "COMPANY_DELETION_CANCELLED";

	/** To every other active member: the company is scheduled for deletion. */
	public static final String NOTICE = "COMPANY_DELETION_NOTICE";

	public static final String AUDIT_REQUESTED = "company.deletion.requested";

	public static final String AUDIT_CANCELLED = "company.deletion.cancelled";

	/** The reason on an audit event whose emails were not queued because of the cooldown. */
	public static final String EMAILS_HELD_BACK = "emails_held_back";

	private DeletionEvents() {
	}

}
