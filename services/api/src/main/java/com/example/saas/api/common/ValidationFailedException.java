package com.example.saas.api.common;

import java.util.List;

/** Input failed server-side validation; becomes a 400 problem with field errors (never echoing values). */
public class ValidationFailedException extends RuntimeException {

	private final List<FieldProblem> errors;

	public ValidationFailedException(List<FieldProblem> errors) {
		super("Validation failed");
		this.errors = List.copyOf(errors);
	}

	public List<FieldProblem> errors() {
		return errors;
	}

}
