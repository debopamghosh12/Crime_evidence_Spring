package com.blockevidence.backend.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically revokes one token if (and only if) it is still active. The return value is the
     * gate for rotation: 1 means this caller won and may issue a replacement, 0 means someone else
     * already used it. A read-then-write here would let two concurrent refreshes both succeed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

    /** Kills every live session of a user, used when refresh-token reuse (likely theft) is detected. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
