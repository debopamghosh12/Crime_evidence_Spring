package com.blockevidence.backend.crypto;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserKeyPairRepository extends JpaRepository<UserKeyPair, UUID> {
}
