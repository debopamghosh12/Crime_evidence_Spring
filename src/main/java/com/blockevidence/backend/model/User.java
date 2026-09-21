package com.blockevidence.backend.model;

import java.time.Instant;
import java.util.UUID;

import com.blockevidence.backend.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A1. Application user. Off-chain only: personal data such as name and department must never be
 * copied to the ledger (constraint C-06). The table is "users" because USER is reserved in
 * PostgreSQL. The schema comes from Flyway (V1), and Hibernate only validates it against this class.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Stored as entered; uniqueness and lookups are case-insensitive (unique index on lower(email)).
    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    public User(String email, String passwordHash, String fullName, String department, Role role,
            Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.department = department;
        this.role = role;
        this.enabled = true;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDepartment() {
        return department;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
