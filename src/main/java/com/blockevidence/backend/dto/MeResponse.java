package com.blockevidence.backend.dto;

import java.util.UUID;

import com.blockevidence.backend.security.Role;

public record MeResponse(UUID id, String email, String fullName, String department, Role role) {
}
