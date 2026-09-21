package com.blockevidence.backend.service;

import java.time.Clock;

import com.blockevidence.backend.domain.VerificationStatus;
import com.blockevidence.backend.dto.ComponentCheck;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.storage.ContentNotFoundException;
import com.blockevidence.backend.storage.IpfsClient;
import org.springframework.stereotype.Service;

/**
 * C2: proves stored content still matches what the ledger recorded. For each artifact (the file, and the
 * metadata document) it re-fetches the bytes from IPFS, re-hashes them, and compares with the SHA-256 on
 * the ledger. Called by EvidenceService for GET /api/evidence/{id}/verify and ?verify=true.
 *
 * <p>Why hashing again matters even though IPFS is content-addressed: the CID only proves the bytes match
 * the CID, and this check proves they match what the LEDGER says was registered. It also catches a node
 * whose stored block was altered or swapped on disk, which content-addressing does not defend against.
 *
 * <p>An unreachable node is NOT reported as NOT_FOUND. It throws StorageUnavailableException (503), because
 * "we could not look" is not evidence that the content is gone.
 */
@Service
public class VerificationService {

    private final IpfsClient ipfs;
    private final Clock clock;

    public VerificationService(IpfsClient ipfs, Clock clock) {
        this.ipfs = ipfs;
        this.clock = clock;
    }

    public VerificationResponse verify(LedgerEvidenceRecord record) {
        ComponentCheck file = record.fileCid() == null ? null : check(record.fileCid(), record.fileSha256());
        ComponentCheck metadata = check(record.metadataCid(), record.metadataSha256());
        return new VerificationResponse(record.evidenceId(), overall(file, metadata), record.version(),
                clock.instant(), file, metadata);
    }

    private ComponentCheck check(String cid, String expectedSha256) {
        try {
            String actual = ipfs.read(cid, Sha256::hex);
            VerificationStatus result = actual.equals(expectedSha256) ? VerificationStatus.VERIFIED
                    : VerificationStatus.TAMPERED;
            return new ComponentCheck(cid, expectedSha256, actual, result);
        } catch (ContentNotFoundException e) {
            return new ComponentCheck(cid, expectedSha256, null, VerificationStatus.NOT_FOUND);
        }
    }

    /** A proven mismatch outranks missing content: TAMPERED, else NOT_FOUND, else VERIFIED. */
    static VerificationStatus overall(ComponentCheck file, ComponentCheck metadata) {
        boolean tampered = false;
        boolean missing = false;
        for (ComponentCheck c : new ComponentCheck[] { file, metadata }) {
            if (c == null) {
                continue;
            }
            tampered |= c.result() == VerificationStatus.TAMPERED;
            missing |= c.result() == VerificationStatus.NOT_FOUND;
        }
        return tampered ? VerificationStatus.TAMPERED : missing ? VerificationStatus.NOT_FOUND
                : VerificationStatus.VERIFIED;
    }
}
