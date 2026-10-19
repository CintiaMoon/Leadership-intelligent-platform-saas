package com.example.saas.api.auth.application;

import java.util.UUID;

/** The account that just proved its identity, as the web layer needs it to open a session. */
public record AuthenticatedAccount(UUID id, String email, String displayName, boolean emailVerified) {
}
