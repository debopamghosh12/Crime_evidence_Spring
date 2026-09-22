package com.blockevidence.backend.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.config.EncryptionProperties;
import com.blockevidence.backend.crypto.ContentKeyService;
import com.blockevidence.backend.crypto.EvidenceContentKey;
import com.blockevidence.backend.crypto.EvidenceContentKeyRepository;
import com.blockevidence.backend.crypto.MasterKey;
import com.blockevidence.backend.crypto.UserKeyPair;
import com.blockevidence.backend.crypto.UserKeyPairRepository;
import com.blockevidence.backend.crypto.UserKeyService;

/**
 * Builds a REAL {@link ContentKeyService}/{@link UserKeyService} (real RSA-2048/AES-256-GCM, not mocked) backed
 * by in-memory, Mockito-stubbed repositories - so a test's register()/get() round trip actually encrypts and
 * decrypts, the same reason {@code FakeIpfsClient} is a real in-memory content store rather than a mock that
 * returns nothing. Tests using this must call {@link UserKeyService#provision} for every {@code AuthenticatedUser}
 * before registering evidence with them (F2 design section 5: wrapping for the registrant is mandatory).
 */
public final class FakeContentKeys {

    private FakeContentKeys() {
    }

    public static UserKeyService userKeyService(Clock clock) {
        UserKeyPairRepository repo = mock(UserKeyPairRepository.class);
        Map<UUID, UserKeyPair> store = new HashMap<>();
        when(repo.existsById(any())).thenAnswer(i -> store.containsKey((UUID) i.getArgument(0)));
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get((UUID) i.getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            UserKeyPair row = i.getArgument(0);
            store.put(row.getUserId(), row);
            return row;
        });
        MasterKey masterKey = new MasterKey(new EncryptionProperties(TestSecrets.ENCRYPTION_MASTER_KEY));
        return new UserKeyService(repo, masterKey, clock);
    }

    public static ContentKeyService contentKeyService(UserKeyService userKeys, Clock clock) {
        EvidenceContentKeyRepository repo = mock(EvidenceContentKeyRepository.class);
        List<EvidenceContentKey> store = new ArrayList<>();
        when(repo.existsByEvidenceIdAndUserId(any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(k -> k.getEvidenceId().equals(i.getArgument(0)) && k.getUserId().equals(i.getArgument(1))));
        when(repo.findByEvidenceIdAndUserId(any(), any())).thenAnswer(i -> store.stream()
                .filter(k -> k.getEvidenceId().equals(i.getArgument(0)) && k.getUserId().equals(i.getArgument(1)))
                .findFirst());
        when(repo.findFirstByEvidenceId(any())).thenAnswer(i -> store.stream()
                .filter(k -> k.getEvidenceId().equals(i.getArgument(0))).findFirst());
        when(repo.save(any())).thenAnswer(i -> {
            EvidenceContentKey row = i.getArgument(0);
            store.add(row);
            return row;
        });
        doAnswer(i -> {
            String evidenceId = i.getArgument(0);
            UUID userId = i.getArgument(1);
            store.removeIf(k -> k.getEvidenceId().equals(evidenceId) && k.getUserId().equals(userId));
            return null;
        }).when(repo).deleteByEvidenceIdAndUserId(any(), any());
        return new ContentKeyService(repo, userKeys, clock);
    }
}
