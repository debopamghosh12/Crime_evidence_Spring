package com.blockevidence.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Case-insensitive to match the unique index on lower(email) in V1.
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
