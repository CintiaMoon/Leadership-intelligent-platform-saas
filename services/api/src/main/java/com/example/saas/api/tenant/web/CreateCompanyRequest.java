package com.example.saas.api.tenant.web;

import jakarta.validation.constraints.Size;

/**
 * Company-creation input. Not annotated for validation on purpose: the name is checked by the service after the rate
 * limits count the attempt. The size cap only bounds the request. Unknown fields, such as a {@code role}, are rejected
 * while parsing (AC-TEN-05).
 */
record CreateCompanyRequest(@Size(max = 400) String name) {
}
