package com.example.saas.api.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record StepUpRequest(@NotNull @Size(max = 1024) String password) {

	@Override
	public String toString() {
		return "StepUpRequest[redacted]";
	}

}
