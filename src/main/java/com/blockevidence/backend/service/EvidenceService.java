package com.blockevidence.backend.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.blockevidence.backend.config.UploadProperties;
import com.blockevidence.backend.domain.Cid;
import com.blockevidence.backend.domain.EvidenceMetadata;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.dto.DisposalDecisionBody;
import com.blockevidence.backend.dto.DisposalRequestBody;
import com.blockevidence.backend.dto.EvidenceResponse;
import com.blockevidence.backend.dto.HistoryEntryResponse;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.dto.UpdateEvidenceRequest;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.LedgerActor;
import com.blockevidence.backend.ledger.LedgerErrorCode;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerException;
import com.blockevidence.backend.ledger.LedgerHistoryEntry;
import com.blockevidence.backend.ledger.LedgerNewEvidence;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.storage.ContentNotFoundException;
import com.blockevidence.backend.storage.IpfsClient;
import com.blockevidence.backend.storage.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * B1-B5, C1, C3: evidence use cases. Called by EvidenceController; talks to the ledger only through
 * LedgerService and to IPFS only through IpfsClient (C-01).
 *
 * <p>Register order (ARCHITECTURE.md 5.4): hash-while-uploading the file to IPFS, then the metadata
 * document to IPFS, and only then the ledger write. The ledger is last so a failure can never leave a
 * ledger entry pointing at content that was never stored. If the ledger write fails, the pins made for it
 * are removed (compensation), except any the ledger still references, see {@link #compensate}.
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final int MAX_METADATA_BYTES = 1024 * 1024;

    private final LedgerService ledger;
    private final IpfsClient ipfs;
    private final VerificationService verification;
    private final JsonMapper json;
    private final UploadProperties upload;

    public EvidenceService(LedgerService ledger, IpfsClient ipfs, VerificationService verification, JsonMapper json,
            UploadProperties upload) {
        this.ledger = ledger;
        this.ipfs = ipfs;
        this.verification = verification;
        this.json = json;
        this.upload = upload;
    }

    private record StoredFile(String cid, String sha256, long size, String name, String contentType) {
    }

    private record StoredDocument(String cid, String sha256) {
    }

    private record MetadataResult(EvidenceMetadata metadata, boolean available) {
        static final MetadataResult UNAVAILABLE = new MetadataResult(null, false);
    }

    // ---------------------------------------------------------------------------------- B1, B2, C1

    /**
     * Registers evidence. {@code user} is the ONLY source of who registered it (C-05): the request DTO has
     * no such field, so nothing a client sends can override it.
     */
    public EvidenceResponse register(RegisterEvidenceRequest request, MultipartFile file, AuthenticatedUser user) {
        boolean digital = request.type() == EvidenceType.DIGITAL;
        boolean hasFile = file != null && !file.isEmpty();
        if (digital && !hasFile) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "DIGITAL evidence requires a file upload");
        }
        if (!digital && file != null && !file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_NOT_ALLOWED", "PHYSICAL evidence cannot have a file");
        }
        if (hasFile) {
            requireAllowedContentType(file.getContentType());
        }

        LedgerActor actor = actor(user);
        String evidenceId = "EV-" + UUID.randomUUID();
        List<String> pinned = new ArrayList<>();
        try {
            StoredFile stored = hasFile ? storeFile(file, pinned) : null;
            EvidenceMetadata metadata = new EvidenceMetadata(EvidenceMetadata.SCHEMA_VERSION, evidenceId,
                    request.caseId(), request.type(), request.description(), request.location(), request.notes(),
                    request.collectedAt(), actor.userId(),
                    stored == null ? null : new EvidenceMetadata.FileInfo(stored.name(), stored.contentType(),
                            stored.size(), stored.sha256()),
                    1, null);
            StoredDocument document = storeMetadata(metadata, pinned);
            ledger.createEvidence(new LedgerNewEvidence(evidenceId, request.caseId(), request.type(), document.cid(),
                    document.sha256(), stored == null ? null : stored.cid(), stored == null ? null : stored.sha256(),
                    stored == null ? null : stored.size()), actor);
        } catch (RuntimeException e) {
            compensate(pinned);
            throw e;
        }
        return get(evidenceId, false);
    }

    private StoredFile storeFile(MultipartFile file, List<String> pinned) {
        String name = safeFileName(file.getOriginalFilename());
        // C1: the hash and byte count are computed while the bytes stream to IPFS, in a single read.
        try (HashingInputStream in = new HashingInputStream(file.getInputStream())) {
            String cid = ipfs.pin(in, name);
            pinned.add(cid);
            if (in.byteCount() != file.getSize()) {
                // The whole upload must have been read; anything else means the CID is for truncated content.
                throw new IllegalStateException("Uploaded " + in.byteCount() + " bytes but the file has "
                        + file.getSize());
            }
            return new StoredFile(cid, in.sha256Hex(), in.byteCount(), name, file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StoredDocument storeMetadata(EvidenceMetadata metadata, List<String> pinned) {
        byte[] bytes = json.writeValueAsBytes(metadata);
        String cid = ipfs.pin(new ByteArrayInputStream(bytes), metadata.evidenceId() + ".v"
                + metadata.metadataVersion() + ".json");
        pinned.add(cid);
        return new StoredDocument(cid, Sha256.hex(bytes));
    }

    /**
     * Undoes pins whose ledger write failed (F5, compensation only; retry is Phase 5). Safety rule: a
     * CID is unpinned ONLY if the ledger confirms no evidence references it. The same file registered
     * twice shares one CID, so blindly unpinning could destroy content another record depends on. If the
     * ledger cannot be asked (the likely reason we are here) nothing is unpinned: an orphaned pin costs
     * disk space, a wrongly removed pin loses evidence.
     */
    private void compensate(List<String> pinned) {
        for (String cid : pinned) {
            try {
                if (ledger.findEvidenceIdsByCid(cid).isEmpty()) {
                    ipfs.unpin(cid);
                }
            } catch (RuntimeException e) {
                log.warn("Left orphaned pin {} in place: could not confirm it is unreferenced ({})", cid,
                        e.getClass().getSimpleName());
            }
        }
    }

    // ------------------------------------------------------------------------------- B3, C3, versions

    public EvidenceResponse get(String evidenceId, boolean verify) {
        LedgerEvidenceRecord record = load(evidenceId);
        return toResponse(record, verify ? verification.verify(record) : VerificationResponse.notChecked(evidenceId));
    }

    public List<EvidenceResponse> findByCid(String cid) {
        if (!Cid.isValid(cid)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CID", "Not a valid content identifier");
        }
        List<EvidenceResponse> found = ledger.findEvidenceIdsByCid(cid).stream().map(id -> get(id, false)).toList();
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No evidence references this CID");
        }
        return found;
    }

    /** B4: any earlier version stays readable, with the metadata document that version pointed at. */
    public EvidenceResponse getVersion(String evidenceId, int version) {
        LedgerEvidenceRecord record = ledger.getHistory(evidenceId).stream()
                .map(LedgerHistoryEntry::record)
                .filter(r -> r.version() == version)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Evidence " + evidenceId + " has no version " + version));
        return toResponse(record, VerificationResponse.notChecked(evidenceId));
    }

    public List<HistoryEntryResponse> history(String evidenceId) {
        return ledger.getHistory(evidenceId).stream().map(entry -> {
            LedgerEvidenceRecord r = entry.record();
            return new HistoryEntryResponse(r.version(), entry.txId(), entry.timestamp(), r.lastAction().name(),
                    r.status(), r.metadataCid(), r.updatedBy(), r.updatedByRole(), r.lastReason());
        }).toList();
    }

    public VerificationResponse verify(String evidenceId) {
        return verification.verify(load(evidenceId));
    }

    // ------------------------------------------------------------------------------------------ B4

    public EvidenceResponse update(String evidenceId, UpdateEvidenceRequest request, AuthenticatedUser user) {
        LedgerEvidenceRecord current = load(evidenceId);
        if (current.version() != request.expectedVersion()) {
            // Checked here too so a stale request fails before anything is written to IPFS.
            throw new LedgerException(LedgerErrorCode.VERSION_CONFLICT, "Expected version " + request.expectedVersion()
                    + " but the record is at version " + current.version());
        }
        EvidenceMetadata base = readVerifiedMetadata(current);
        EvidenceMetadata next = new EvidenceMetadata(EvidenceMetadata.SCHEMA_VERSION, base.evidenceId(),
                base.caseId(), base.evidenceType(),
                request.description() != null ? request.description() : base.description(),
                request.location() != null ? request.location() : base.location(),
                request.notes() != null ? request.notes() : base.notes(),
                base.collectedAt(), base.collectorId(), base.file(), base.metadataVersion() + 1,
                current.metadataCid());

        List<String> pinned = new ArrayList<>();
        try {
            StoredDocument document = storeMetadata(next, pinned);
            ledger.updateEvidence(evidenceId, request.expectedVersion(), document.cid(), document.sha256(),
                    request.reason(), actor(user));
        } catch (RuntimeException e) {
            compensate(pinned);
            throw e;
        }
        return get(evidenceId, false);
    }

    /**
     * The current metadata is the base of the next version, so it is hash-checked against the ledger first:
     * building on tampered metadata would launder the tampering into a fresh, ledger-blessed version.
     */
    private EvidenceMetadata readVerifiedMetadata(LedgerEvidenceRecord record) {
        byte[] bytes;
        try {
            bytes = ipfs.read(record.metadataCid(), in -> in.readNBytes(MAX_METADATA_BYTES + 1));
        } catch (ContentNotFoundException e) {
            throw new ApiException(HttpStatus.CONFLICT, "METADATA_UNAVAILABLE",
                    "The current metadata document is missing from storage, so it cannot be updated");
        }
        if (bytes.length > MAX_METADATA_BYTES || !Sha256.hex(bytes).equals(record.metadataSha256())) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRITY_CHECK_FAILED",
                    "The stored metadata does not match the ledger hash; refusing to build on it");
        }
        try {
            return json.readValue(bytes, EvidenceMetadata.class);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRITY_CHECK_FAILED", "The stored metadata is unreadable");
        }
    }

    // ------------------------------------------------------------------------------------------ B5

    public EvidenceResponse requestDisposal(String evidenceId, DisposalRequestBody body, AuthenticatedUser user) {
        ledger.requestDisposal(evidenceId, body.expectedVersion(), body.reason(), actor(user));
        return get(evidenceId, false);
    }

    public EvidenceResponse approveDisposal(String evidenceId, DisposalDecisionBody body, AuthenticatedUser user) {
        ledger.approveDisposal(evidenceId, body.expectedVersion(), body.note(), actor(user));
        return get(evidenceId, false);
    }

    public EvidenceResponse rejectDisposal(String evidenceId, DisposalDecisionBody body, AuthenticatedUser user) {
        ledger.rejectDisposal(evidenceId, body.expectedVersion(), body.note(), actor(user));
        return get(evidenceId, false);
    }

    // ----------------------------------------------------------------------------------------- shared

    private LedgerEvidenceRecord load(String evidenceId) {
        return ledger.getEvidence(evidenceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Evidence " + evidenceId + " does not exist"));
    }

    private static LedgerActor actor(AuthenticatedUser user) {
        return new LedgerActor(user.userId().toString(), user.role());
    }

    private void requireAllowedContentType(String contentType) {
        String base = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!upload.allowedContentTypes().contains(base)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "File type '" + (base.isEmpty() ? "unknown" : base) + "' is not allowed");
        }
    }

    /** Client filenames are untrusted: drop any directory part and control characters, cap the length. */
    static String safeFileName(String original) {
        if (original == null) {
            return "upload";
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            return "upload";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private MetadataResult readMetadata(String cid) {
        try {
            byte[] bytes = ipfs.read(cid, in -> in.readNBytes(MAX_METADATA_BYTES + 1));
            if (bytes.length > MAX_METADATA_BYTES) {
                return MetadataResult.UNAVAILABLE;
            }
            return new MetadataResult(json.readValue(bytes, EvidenceMetadata.class), true);
        } catch (ContentNotFoundException | StorageUnavailableException | JacksonException e) {
            // F1: a storage problem must not hide the ledger information, so degrade instead of failing.
            log.warn("Metadata {} unavailable: {}", cid, e.getClass().getSimpleName());
            return MetadataResult.UNAVAILABLE;
        }
    }

    private EvidenceResponse toResponse(LedgerEvidenceRecord r, VerificationResponse verificationResult) {
        MetadataResult meta = readMetadata(r.metadataCid());
        var d = r.disposal();
        return new EvidenceResponse(r.evidenceId(), r.caseId(), r.evidenceType(), r.status(), r.version(),
                r.currentCustodian(), r.metadataCid(), r.metadataSha256(), r.fileCid(), r.fileSha256(), r.fileSize(),
                r.createdBy(), r.createdAt(), r.updatedBy(), r.updatedAt(), r.lastAction().name(), r.lastReason(),
                new EvidenceResponse.Disposal(d.state().name(), d.requestedBy(), d.requestedAt(), d.reason()),
                meta.available(), meta.metadata(), verificationResult);
    }
}
