package com.example.saas.api.plan.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** One version of a plan (AC-PLAN-16). Reference data written only by migrations; the application never changes it. */
@Entity
@Immutable
@Table(name = "plan")
public class Plan {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private String code;

	@Column(nullable = false, updatable = false)
	private int version;

	@Column(nullable = false, updatable = false)
	private String name;

	protected Plan() {
	}

	public UUID getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public int getVersion() {
		return version;
	}

	public String getName() {
		return name;
	}

}
