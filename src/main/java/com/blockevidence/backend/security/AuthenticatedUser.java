package com.blockevidence.backend.security;

import java.util.UUID;

/**
 * The authenticated principal, rebuilt from the verified JWT on every request. This is the ONLY
 * legitimate source of "who is acting" (constraint C-05): controllers take it with
 * {@code @AuthenticationPrincipal}, and no request body may carry an acting-officer field.
 */
public record AuthenticatedUser(UUID userId, String email, Role role) {
}
