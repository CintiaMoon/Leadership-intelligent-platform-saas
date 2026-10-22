package com.example.saas.api.tenant.application;

import java.util.UUID;

/**
 * Whether a company may have one more ACTIVE member (AC-MEM-17). Called when a suspended membership is reactivated,
 * after the company row is locked, so two concurrent reactivations cannot both take the last place. Slice 003 allows
 * everything; slice 006 supplies the plan-based implementation and decides the error it maps to.
 */
public interface ActiveMemberLimit {

	boolean hasRoomForAnotherActiveMember(UUID companyId);

}
