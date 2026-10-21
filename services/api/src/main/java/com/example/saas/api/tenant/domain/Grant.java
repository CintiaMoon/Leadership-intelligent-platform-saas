package com.example.saas.api.tenant.domain;

/** What one matrix cell says for one role (SPEC-TEN). */
public enum Grant {

	/** "Yes". */
	ALLOWED,

	/** "No". */
	DENIED,

	/** "Agent/Viewer only": allowed only when the target membership's role is Agent or Viewer. */
	TARGET_AGENT_OR_VIEWER,

	/** "View only": read access to the data without the right to change it. Never allows this permission. */
	VIEW_ONLY,

	/** "System process": no user role may do it. */
	SYSTEM_ONLY

}
