package com.example.saas.api.plan.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.plan.domain.SubscriptionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A company's subscription: the state last recorded and the trial instants. Decisions use
 * {@code SubscriptionState.effective}, never the recorded state alone (AC-PLAN-10). Never serialized to an API response.
 */
@Entity
@Table(name = "company_subscription")
public class CompanySubscription {

	/** A trial and its read-only window are each 14 days, counted in hours so no time zone can move them (D-34). */
	public static final Duration TRIAL_LENGTH = Duration.ofHours(336);

	@Id
	@Column(name = "company_id")
	private UUID companyId;

	@Column(name = "plan_id")
	private UUID planId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SubscriptionState state;

	@Column(name = "trial_started_at")
	private Instant trialStartedAt;

	@Column(name = "trial_ends_at")
	private Instant trialEndsAt;

	@Column(name = "read_only_ends_at")
	private Instant readOnlyEndsAt;

	@Column(name = "state_changed_at", nullable = false)
	private Instant stateChangedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	protected CompanySubscription() {
	}

	/** A company with no plan and no trial (D-45: not the user's first company). */
	public static CompanySubscription noPlan(UUID companyId, Instant now) {
		CompanySubscription s = new CompanySubscription();
		s.companyId = companyId;
		s.state = SubscriptionState.NO_PLAN;
		s.stateChangedAt = now;
		s.createdAt = now;
		s.updatedAt = now;
		return s;
	}

	/** A Trial starting now on the given Trial plan version; the end instants are computed here, never supplied. */
	public static CompanySubscription trial(UUID companyId, UUID trialPlanId, Instant now) {
		CompanySubscription s = noPlan(companyId, now);
		s.planId = trialPlanId;
		s.state = SubscriptionState.TRIALING;
		s.trialStartedAt = now;
		s.trialEndsAt = now.plus(TRIAL_LENGTH);
		s.readOnlyEndsAt = s.trialEndsAt.plus(TRIAL_LENGTH);
		return s;
	}

	/** Records a transition the table allows; the caller has checked it and holds the company lock. */
	public void recordState(SubscriptionState next, Instant now) {
		this.state = next;
		this.stateChangedAt = now;
		this.updatedAt = now;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public UUID getPlanId() {
		return planId;
	}

	public SubscriptionState getState() {
		return state;
	}

	public Instant getTrialStartedAt() {
		return trialStartedAt;
	}

	public Instant getTrialEndsAt() {
		return trialEndsAt;
	}

	public Instant getReadOnlyEndsAt() {
		return readOnlyEndsAt;
	}

	public SubscriptionState effectiveAt(Instant now) {
		return SubscriptionState.effective(state, trialEndsAt, readOnlyEndsAt, now);
	}

}
