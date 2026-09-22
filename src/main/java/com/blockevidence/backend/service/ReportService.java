package com.blockevidence.backend.service;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.blockevidence.backend.dto.ComponentCheck;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.exception.ApiException;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerHistoryEntry;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * I1: a one-file chain-of-custody PDF, built ENTIRELY from {@link LedgerService} and
 * {@link VerificationService} - evidence details, the full history, the hashes and transaction ids the ledger
 * already recorded, and a fresh verification result. Deliberately has no dependency on
 * {@code ContentKeyService}, {@code IpfsClient} or {@code EvidenceMetadata} - it is architecturally incapable of
 * needing a decrypted file or metadata document, not just written to avoid it. This was a design point the
 * owner asked to be confirmed before any code was written: {@link VerificationService#verify} already needs no
 * content key (only IPFS reachability, to re-fetch ciphertext and hash it, proven live in F2/F3), so a Judge or
 * Auditor who was never wrapped in for file decryption generates the exact same report as one who was.
 *
 * <p>The report intentionally excludes the off-chain metadata document's free-text description/location/notes -
 * that is the CONTENT F2/F3 protects, not a chain-of-custody fact. "Evidence details" here means the
 * ledger-native record only (case, type, status, custodian, timestamps): the same design line I3 already draws
 * ("see integrity status only, without seeing the content").
 */
@Service
public class ReportService {

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

    private final LedgerService ledger;
    private final VerificationService verification;
    private final Clock clock;

    public ReportService(LedgerService ledger, VerificationService verification, Clock clock) {
        this.ledger = ledger;
        this.verification = verification;
        this.clock = clock;
    }

    public byte[] generateChainOfCustodyReport(String evidenceId, AuthenticatedUser requestedBy) {
        LedgerEvidenceRecord record = ledger.getEvidence(evidenceId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Evidence " + evidenceId + " does not exist"));
        List<LedgerHistoryEntry> history = ledger.getHistory(evidenceId);
        VerificationResponse result = verification.verify(record);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 54, 54);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            writeHeader(document, record, requestedBy);
            writeEvidenceDetails(document, record);
            writeHashes(document, record);
            writeTimeline(document, history);
            writeVerification(document, result);
            writeFooter(document, requestedBy);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not generate the chain-of-custody report", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private void writeHeader(Document document, LedgerEvidenceRecord record, AuthenticatedUser requestedBy)
            throws DocumentException {
        Paragraph title = new Paragraph("Chain of Custody Report", TITLE_FONT);
        title.setSpacingAfter(4);
        document.add(title);
        Paragraph sub = new Paragraph("Evidence " + record.evidenceId() + " - Case " + record.caseId(), BODY_FONT);
        sub.setSpacingAfter(2);
        document.add(sub);
        Paragraph generated = new Paragraph(
                "Generated " + clock.instant() + " by " + requestedBy.email() + " (" + requestedBy.role() + ")",
                SMALL_FONT);
        generated.setSpacingAfter(16);
        document.add(generated);
    }

    private void writeEvidenceDetails(Document document, LedgerEvidenceRecord record) throws DocumentException {
        section(document, "Evidence details");
        PdfPTable table = keyValueTable();
        row(table, "Evidence ID", record.evidenceId());
        row(table, "Case ID", record.caseId());
        row(table, "Type", record.evidenceType().name());
        row(table, "Status", record.status().name());
        row(table, "Ledger version", String.valueOf(record.version()));
        row(table, "Current custodian", record.currentCustodian());
        row(table, "Created by", record.createdBy() + " (" + record.createdByRole() + ")");
        row(table, "Created at", String.valueOf(record.createdAt()));
        row(table, "Last updated by", record.updatedBy() + " (" + record.updatedByRole() + ")");
        row(table, "Last updated at", String.valueOf(record.updatedAt()));
        row(table, "Last action", record.lastAction().name());
        row(table, "Last reason", blank(record.lastReason()));
        document.add(table);
        document.add(spacer());
    }

    private void writeHashes(Document document, LedgerEvidenceRecord record) throws DocumentException {
        section(document, "Content hashes (recorded on the ledger)");
        PdfPTable table = keyValueTable();
        row(table, "Metadata CID", record.metadataCid());
        row(table, "Metadata SHA-256", record.metadataSha256());
        if (record.fileCid() != null) {
            row(table, "File CID", record.fileCid());
            row(table, "File SHA-256", record.fileSha256());
            row(table, "File size (bytes)", String.valueOf(record.fileSize()));
        } else {
            row(table, "File", "(none - PHYSICAL evidence)");
        }
        document.add(table);
        document.add(spacer());
    }

    /**
     * Two tables, not one: a 64-hex-character transaction id and a 36-character actor UUID both need
     * dedicated width to stay on a single line (found live - crammed into one 7-column row on A4 portrait,
     * both wrapped mid-string, which is unreadable in the actual PDF and not just a test artifact). Splitting
     * keeps the narrative columns (timestamp/action/role/reason) scannable and gives the two long tokens - the
     * ones the owner specifically wants checkable against the real ledger - room to render intact.
     */
    private void writeTimeline(Document document, List<LedgerHistoryEntry> history) throws DocumentException {
        section(document, "Full timeline (" + history.size() + " ledger " + (history.size() == 1 ? "entry" : "entries") + ")");
        PdfPTable events = new PdfPTable(new float[] { 1f, 4f, 4f, 2.5f, 6f });
        events.setWidthPercentage(100);
        for (String h : new String[] { "Ver", "Ledger timestamp", "Action", "Role", "Reason" }) {
            events.addCell(headerCell(h));
        }
        for (LedgerHistoryEntry entry : history) {
            LedgerEvidenceRecord r = entry.record();
            events.addCell(bodyCell(String.valueOf(r.version())));
            events.addCell(bodyCell(String.valueOf(entry.timestamp())));
            events.addCell(bodyCell(r.lastAction().name()));
            events.addCell(bodyCell(r.updatedByRole()));
            events.addCell(bodyCell(blank(r.lastReason())));
        }
        document.add(events);
        document.add(spacer());

        section(document, "Actors and transaction ids (by version, for independent ledger verification)");
        PdfPTable ids = new PdfPTable(new float[] { 1f, 5f, 9f });
        ids.setWidthPercentage(100);
        for (String h : new String[] { "Ver", "Actor", "Transaction ID" }) {
            ids.addCell(headerCell(h));
        }
        for (LedgerHistoryEntry entry : history) {
            ids.addCell(bodyCell(String.valueOf(entry.record().version())));
            ids.addCell(bodyCell(entry.record().updatedBy()));
            ids.addCell(bodyCell(entry.txId()));
        }
        document.add(ids);
        document.add(spacer());
    }

    private void writeVerification(Document document, VerificationResponse result) throws DocumentException {
        section(document, "Verification result (checked at report generation time)");
        PdfPTable summary = keyValueTable();
        row(summary, "Overall status", result.status().name());
        row(summary, "Checked at", String.valueOf(result.checkedAt()));
        document.add(summary);
        if (result.file() != null) {
            document.add(componentTable("File", result.file()));
        }
        if (result.metadata() != null) {
            document.add(componentTable("Metadata document", result.metadata()));
        }
    }

    private PdfPTable componentTable(String label, ComponentCheck check) throws DocumentException {
        PdfPTable table = keyValueTable();
        row(table, label + " CID", check.cid());
        row(table, label + " expected SHA-256", blank(check.expectedSha256()));
        row(table, label + " actual SHA-256", blank(check.actualSha256()));
        row(table, label + " result", check.result().name());
        return table;
    }

    private void writeFooter(Document document, AuthenticatedUser requestedBy) throws DocumentException {
        Paragraph note = new Paragraph(
                "This report is generated directly from the Hyperledger Fabric ledger and re-verified IPFS "
                        + "content hashes at generation time; it contains no personal data and no file content "
                        + "(F2/F3, C-06). Report requested by " + requestedBy.email() + ".",
                SMALL_FONT);
        note.setSpacingBefore(10);
        document.add(note);
    }

    private void section(Document document, String heading) throws DocumentException {
        Paragraph p = new Paragraph(heading, SECTION_FONT);
        p.setSpacingBefore(6);
        p.setSpacingAfter(6);
        document.add(p);
    }

    private PdfPTable keyValueTable() {
        PdfPTable table = new PdfPTable(new float[] { 2.5f, 7.5f });
        table.setWidthPercentage(100);
        return table;
    }

    private void row(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        labelCell.setBorder(Rectangle.BOTTOM);
        labelCell.setBorderWidthBottom(0.5f);
        labelCell.setPadding(3);
        table.addCell(labelCell);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, BODY_FONT));
        valueCell.setBorder(Rectangle.BOTTOM);
        valueCell.setBorderWidthBottom(0.5f);
        valueCell.setPadding(3);
        table.addCell(valueCell);
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEADER_FONT));
        cell.setGrayFill(0.85f);
        cell.setPadding(4);
        return cell;
    }

    private PdfPCell bodyCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, SMALL_FONT));
        cell.setPadding(4);
        return cell;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private Paragraph spacer() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(2);
        return p;
    }
}
