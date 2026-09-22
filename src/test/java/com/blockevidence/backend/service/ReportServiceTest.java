package com.blockevidence.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.InMemoryLedgerService;
import com.blockevidence.backend.ledger.LedgerActor;
import com.blockevidence.backend.ledger.LedgerHistoryEntry;
import com.blockevidence.backend.ledger.LedgerNewEvidence;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.support.FakeIpfsClient;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

/**
 * I1 (docs/features/i1-chain-of-custody-report.md): built on the REAL reference ledger and a real (in-memory)
 * IPFS node, with a genuine multi-step timeline (register, status change, transfer, disposal), then the
 * generated PDF is read back and its TEXT CONTENT is asserted against the exact ledger values - not just "no
 * exception was thrown". Confirms the design point the owner asked to be checked before writing any code: the
 * report never touches content decryption, only {@code LedgerService} and {@code VerificationService}.
 */
class ReportServiceTest {

    static final byte[] FILE_BYTES = "the seized phone image, byte for byte".getBytes(StandardCharsets.UTF_8);
    static final byte[] METADATA_BYTES = "{\"description\":\"irrelevant to this report\"}".getBytes(StandardCharsets.UTF_8);
    static final String EV1 = "EV-11111111-1111-1111-1111-111111111111";
    static final String EV2 = "EV-22222222-2222-2222-2222-222222222222";

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final FakeIpfsClient ipfs = new FakeIpfsClient();
    final InMemoryLedgerService ledger = new InMemoryLedgerService(clock);
    final VerificationService verification = new VerificationService(ipfs, clock);
    final ReportService service = new ReportService(ledger, verification, clock);

    final UUID collectorId = UUID.randomUUID();
    final UUID analystId = UUID.randomUUID();
    final UUID prosecutorId = UUID.randomUUID();
    final AuthenticatedUser requestedBy = new AuthenticatedUser(UUID.randomUUID(), "judge@example.org", Role.JUDGE);

    static String extractAllText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        reader.close();
        return text.toString();
    }

    @Test
    void reportContainsEvidenceDetailsTheFullTimelineHashesTxIdsAndTheVerificationResult() throws IOException {
        String fileCid = ipfs.pin(new ByteArrayInputStream(FILE_BYTES), "phone.bin");
        String fileSha256 = Sha256.hex(FILE_BYTES);
        String metadataCid = ipfs.pin(new ByteArrayInputStream(METADATA_BYTES), "m.json");
        String metadataSha256 = Sha256.hex(METADATA_BYTES);

        LedgerActor collector = new LedgerActor(collectorId.toString(), Role.COLLECTOR);
        LedgerActor analyst = new LedgerActor(analystId.toString(), Role.FORENSIC_ANALYST);
        LedgerActor prosecutor = new LedgerActor(prosecutorId.toString(), Role.PROSECUTOR);

        // A real, multi-step chain of custody: register -> status change -> transfer -> disposal request.
        ledger.createEvidence(new LedgerNewEvidence(EV1, "CASE-REPORT-1", EvidenceType.DIGITAL, metadataCid,
                metadataSha256, fileCid, fileSha256, (long) FILE_BYTES.length), collector);
        ledger.updateStatus(EV1, 1, EvidenceStatus.PROCESSING, "sent to forensic lab", analyst);
        ledger.initiateTransfer(EV1, 2, analystId.toString(), Role.FORENSIC_ANALYST,
                "forensic analysis", "sealed bag 7", collector);
        ledger.acceptTransfer(EV1, 3, "received intact", analyst);
        ledger.requestDisposal(EV1, 4, "case closed", prosecutor);

        List<LedgerHistoryEntry> history = ledger.getHistory(EV1);
        assertThat(history).as("sanity check: a genuine 5-entry timeline").hasSize(5);

        byte[] pdf = service.generateChainOfCustodyReport(EV1, requestedBy);
        String text = extractAllText(pdf);

        // Evidence details
        assertThat(text).contains(EV1, "CASE-REPORT-1", "DIGITAL", "COLLECTOR");
        // Hashes recorded on the ledger
        assertThat(text).contains(fileCid, fileSha256, metadataCid, metadataSha256);
        // Every transaction id from the real history, verbatim
        for (LedgerHistoryEntry entry : history) {
            assertThat(text).as("tx id %s must appear in the report", entry.txId()).contains(entry.txId());
        }
        // Every action in the real timeline
        assertThat(text).contains("CREATED", "STATUS_CHANGED", "TRANSFER_INITIATED", "TRANSFER_ACCEPTED",
                "DISPOSAL_REQUESTED");
        // The actors who performed each step
        assertThat(text).contains(collectorId.toString(), analystId.toString(), prosecutorId.toString());
        // A fresh verification result - content was pinned to match, so it comes back VERIFIED
        assertThat(text).contains("VERIFIED");
        // The requester is recorded, but the report contains no decrypted description
        assertThat(text).contains(requestedBy.email());
        assertThat(text).doesNotContain("irrelevant to this report");
    }

    @Test
    void reportForUnknownEvidenceIs404() {
        assertThatThrownBy(() -> service.generateChainOfCustodyReport("EV-" + UUID.randomUUID(), requestedBy))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aTamperedComponentIsReflectedInTheReport() throws IOException {
        String fileCid = ipfs.pin(new ByteArrayInputStream(FILE_BYTES), "f");
        String fileSha256 = Sha256.hex(FILE_BYTES);
        String metadataCid = ipfs.pin(new ByteArrayInputStream(METADATA_BYTES), "m");
        String metadataSha256 = Sha256.hex(METADATA_BYTES);
        LedgerActor collector = new LedgerActor(collectorId.toString(), Role.COLLECTOR);
        ledger.createEvidence(new LedgerNewEvidence(EV2, "CASE-REPORT-1", EvidenceType.DIGITAL, metadataCid,
                metadataSha256, fileCid, fileSha256, (long) FILE_BYTES.length), collector);
        ipfs.corrupt(fileCid, "corrupted bytes, not the original file".getBytes(StandardCharsets.UTF_8));

        String text = extractAllText(service.generateChainOfCustodyReport(EV2, requestedBy));

        assertThat(text).contains("TAMPERED");
    }
}
