package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.tenant.domain.Permission;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records a denial in its own transaction, so it survives the failed request (D-115). */
@Component
class DenialAudit {

	private final AuditLog audit;

	DenialAudit(AuditLog audit) {
		this.audit = audit;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void denied(UUID userId, UUID companyId, Permission permission) {
		audit.recordForCompany("tenant.authorization.denied", AuditLog.Actor.user, userId, "company", companyId,
				companyId, "denied", permission.code());
	}

}
