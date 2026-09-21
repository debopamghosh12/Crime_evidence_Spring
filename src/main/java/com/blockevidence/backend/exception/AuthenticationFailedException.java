package com.blockevidence.backend.exception;

/**
 * Thrown by AuthService for bad credentials or an unusable refresh token. Mapped to 401
 * UNAUTHENTICATED. Callers deliberately pass the same message for "no such user", "wrong password"
 * and "account disabled" so a client cannot use the response to discover which accounts exist.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
