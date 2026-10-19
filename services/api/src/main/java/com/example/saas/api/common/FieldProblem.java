package com.example.saas.api.common;

/** One field-level validation error. {@code code} is stable, for example REQUIRED or INVALID_LENGTH. */
public record FieldProblem(String field, String code) {
}
