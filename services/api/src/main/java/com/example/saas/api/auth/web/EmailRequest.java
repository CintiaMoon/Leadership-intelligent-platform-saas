package com.example.saas.api.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record EmailRequest(@NotNull @Size(max = 254) String email) {
}
