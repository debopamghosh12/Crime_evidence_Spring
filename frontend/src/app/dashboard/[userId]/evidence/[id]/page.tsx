"use client";

import { useEffect, useState } from "react";
import api from "@/lib/api";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import {
    ArrowLeft,
    MapPin,
    Calendar,
    User,
    ShieldCheck,
    ShieldAlert,
    ShieldQuestion,
    FileText,
    History,
    Loader2,
    Download,
    Trash2
} from "lucide-react";
import { cn } from "@/lib/utils";

// Matches com.blockevidence.backend.dto.EvidenceResponse exactly. Top-level fields are the LEDGER's
// view (source of truth); metadata is the off-chain descriptive document, null (with
// metadataAvailable=false) when IPFS can't supply it - never when it simply doesn't exist for this
// item. currentCustodian/createdBy/updatedBy are plain user-id strings (no user-lookup endpoint, A4
// was never built).
interface EvidenceDetail {
    evidenceId: string;
    caseId: string;
    evidenceType: "PHYSICAL" | "DIGITAL";
    status: string;
    version: number;
    currentCustodian: string;
    metadataCid: string;
    metadataSha256: string;
    fileCid: string | null;
    fileSha256: string | null;
    fileSize: number | null;
    createdBy: string;
    createdAt: string;
    lastAction: string;
    lastReason: string;
    disposal: { state: string; requestedBy?: string; requestedAt?: string; reason?: string };
    metadataAvailable: boolean;
    metadata: {
        description: string;
        location: string | null;
        notes: string | null;
        collectedAt: string;
        file: { originalName: string; contentType: string; size: number; sha256: string } | null;
    } | null;
}

// Matches com.blockevidence.backend.dto.VerificationResponse exactly (GET /api/evidence/{id}/verify, C2).
interface VerificationResponse {
    status: "VERIFIED" | "TAMPERED" | "NOT_FOUND" | "NOT_CHECKED";
    ledgerVersion: number | null;
    checkedAt: string | null;
    file: { cid: string; expectedSha256: string; actualSha256: string; result: string } | null;
    metadata: { cid: string; expectedSha256: string; actualSha256: string; result: string } | null;
}

// Matches com.blockevidence.backend.dto.HistoryEntryResponse exactly (GET /api/evidence/{id}/history, C3).
// txId/timestamp are the LEDGER's, not the server's.
interface HistoryEntry {
    version: number;
    txId: string;
    timestamp: string;
    action: string;
    status: string;
    metadataCid: string;
    actorId: string;
    actorRole: string;
    reason: string;
}

export default function EvidenceDetailPage() {
    const params = useParams();
    const id = params.id as string;
    const userId = params.userId as string;
    const { user } = useAuth();
    const [evidence, setEvidence] = useState<EvidenceDetail | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    // Version history (C3) - real, from the ledger's own GetHistoryForKey, not the off-chain projection.
    const [history, setHistory] = useState<HistoryEntry[]>([]);
    const [historyLoading, setHistoryLoading] = useState(true);

    const fetchHistory = async () => {
        try {
            const response = await api.get(`/api/evidence/${id}/history`);
            setHistory(response.data);
        } catch (e) { console.error(e); }
        finally { setHistoryLoading(false); }
    };

    // Transfer Modal State (D2 step 1 of 2 - initiate; see custody/page.tsx for step 2, accept/reject)
    const [transferModalOpen, setTransferModalOpen] = useState(false);
    const [transferReason, setTransferReason] = useState("");
    const [targetUserId, setTargetUserId] = useState("");
    const [transferLoading, setTransferLoading] = useState(false);
    const [transferError, setTransferError] = useState("");

    const fetchEvidence = async () => {
        try {
            const response = await api.get(`/api/evidence/${id}`);
            setEvidence(response.data);
        } catch {
            setError("Failed to load evidence details.");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (id) {
            fetchEvidence();
            fetchHistory();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [id]);

    const handleTransfer = async () => {
        if (!targetUserId || !transferReason || !evidence) return;
        setTransferLoading(true);
        setTransferError("");
        try {
            // D2 step 1: initiate only - custody does NOT move here. toUserId is a real user UUID;
            // there's no user-lookup endpoint (A4 was never built), so the caller must know the id.
            await api.post(`/api/evidence/${id}/transfers`, {
                expectedVersion: evidence.version,
                toUserId: targetUserId,
                reason: transferReason,
            });
            setTransferModalOpen(false);
            setTargetUserId("");
            setTransferReason("");
            await fetchEvidence();
        } catch (error: unknown) {
            const err = error as { response?: { data?: { message?: string } } };
            setTransferError(err.response?.data?.message || "Transfer failed");
        } finally {
            setTransferLoading(false);
        }
    };

    // Disposal (B5): request (COLLECTOR/PROSECUTOR) is step 1, approve/reject (JUDGE only) is step 2 -
    // the ONLY path to DISPOSED status. Nothing is ever deleted (C-02) - status only.
    const [disposalModalOpen, setDisposalModalOpen] = useState(false);
    const [disposalReason, setDisposalReason] = useState("");
    const [disposalLoading, setDisposalLoading] = useState(false);
    const [disposalError, setDisposalError] = useState("");

    const handleRequestDisposal = async () => {
        if (!disposalReason || !evidence) return;
        setDisposalLoading(true);
        setDisposalError("");
        try {
            await api.post(`/api/evidence/${id}/disposal`, {
                expectedVersion: evidence.version,
                reason: disposalReason,
            });
            setDisposalModalOpen(false);
            setDisposalReason("");
            await Promise.all([fetchEvidence(), fetchHistory()]);
        } catch (error: unknown) {
            const err = error as { response?: { data?: { message?: string } } };
            setDisposalError(err.response?.data?.message || "Disposal request failed");
        } finally {
            setDisposalLoading(false);
        }
    };

    const [decisionNote, setDecisionNote] = useState("");
    const [decisionLoading, setDecisionLoading] = useState<"approve" | "reject" | null>(null);
    const [decisionError, setDecisionError] = useState("");
    const [decisionSuccess, setDecisionSuccess] = useState("");

    const handleDisposalDecision = async (decision: "approve" | "reject") => {
        if (!evidence || !decisionNote.trim()) return;
        setDecisionLoading(decision);
        setDecisionError("");
        setDecisionSuccess("");
        try {
            // Role: JUDGE only (Permissions.DECIDE_DISPOSAL) - any other role gets a real 403 here,
            // surfaced below rather than hidden, matching every other authorization check in this app.
            // Unlike TransferDecisionBody's optional note, DisposalDecisionBody.note is @NotBlank -
            // mandatory, found live (a real 400 "Request validation failed" the first time this was
            // tried without one), not the 403 expected - fixed by requiring it here too.
            await api.post(`/api/evidence/${id}/disposal/${decision}`, {
                expectedVersion: evidence.version,
                note: decisionNote,
            });
            setDecisionNote("");
            setDecisionSuccess(decision === "approve" ? "Disposal approved - status is now DISPOSED." : "Disposal rejected - status unchanged.");
            await Promise.all([fetchEvidence(), fetchHistory()]);
        } catch (error: unknown) {
            const err = error as { response?: { status?: number; data?: { message?: string } } };
            if (err.response?.status === 403) {
                setDecisionError("403 Forbidden - only a JUDGE may approve or reject a disposal request.");
            } else {
                setDecisionError(err.response?.data?.message || `Disposal ${decision} failed`);
            }
        } finally {
            setDecisionLoading(null);
        }
    };

    // Verify integrity (C2). Authenticated, by evidence ID - the real backend has no public by-hash
    // lookup, so there is no standalone /verify/[hash] page here; this is a per-item action instead.
    const [verifyResult, setVerifyResult] = useState<VerificationResponse | null>(null);
    const [verifying, setVerifying] = useState(false);
    const [verifyError, setVerifyError] = useState("");

    const handleVerify = async () => {
        setVerifying(true);
        setVerifyError("");
        setVerifyResult(null);
        try {
            // Deliberately needs NO content key - re-fetches and re-hashes the stored ciphertext and
            // compares against the ledger, same as I1's report.
            const response = await api.get(`/api/evidence/${id}/verify`);
            setVerifyResult(response.data);
        } catch (error: unknown) {
            const err = error as { response?: { data?: { message?: string } } };
            setVerifyError(err.response?.data?.message || "Verification failed");
        } finally {
            setVerifying(false);
        }
    };

    const [downloadError, setDownloadError] = useState("");

    const handleDownload = async () => {
        if (!evidence) return;
        setDownloadError("");
        try {
            // F2/F3: decrypts server-side before sending. 403 KEY_NOT_AUTHORISED if the caller was
            // never wrapped in for this item's content key - a real, previously-verified restriction,
            // not simulated here.
            const response = await api.get(`/api/evidence/${id}/file`, { responseType: "blob" });
            const url = window.URL.createObjectURL(new Blob([response.data]));
            const a = document.createElement("a");
            a.href = url;
            a.download = evidence.metadata?.file?.originalName || `${evidence.evidenceId}-file`;
            a.click();
            window.URL.revokeObjectURL(url);
        } catch (error: unknown) {
            const err = error as { response?: { status?: number; data?: { message?: string } } };
            if (err.response?.status === 403) {
                setDownloadError("You are not authorised to decrypt this file's content key (F2/F3).");
            } else {
                setDownloadError(err.response?.data?.message || "Download failed");
            }
        }
    };

    const [reportGenerating, setReportGenerating] = useState(false);
    const [reportError, setReportError] = useState("");

    const handleReport = async () => {
        if (!evidence) return;
        setReportGenerating(true);
        setReportError("");
        try {
            // I1: built entirely from the ledger + a fresh verify() result - deliberately needs NO
            // content key, same as Verify Integrity above. A Judge/Auditor never wrapped in for this
            // item's content generates the exact same report as one who was.
            const response = await api.get(`/api/evidence/${id}/report`, { responseType: "blob" });
            const url = window.URL.createObjectURL(new Blob([response.data], { type: "application/pdf" }));
            const a = document.createElement("a");
            a.href = url;
            a.download = `${evidence.evidenceId}-chain-of-custody.pdf`;
            a.click();
            window.URL.revokeObjectURL(url);
        } catch (error: unknown) {
            const err = error as { response?: { data?: { message?: string } } };
            setReportError(err.response?.data?.message || "Report generation failed");
        } finally {
            setReportGenerating(false);
        }
    };

    if (loading) {
        return (
            <div className="flex h-64 items-center justify-center">
                <Loader2 className="h-8 w-8 animate-spin text-primary" />
            </div>
        );
    }

    if (error || !evidence) {
        return (
            <div className="space-y-4 text-center">
                <p className="text-destructive">{error || "Evidence not found."}</p>
                <Link href={`/dashboard/${userId}/evidence`} className="text-primary hover:underline">
                    Return to Evidence Log
                </Link>
            </div>
        );
    }

    return (
        <div className="space-y-8 animate-in fade-in duration-500 relative">
            {/* Transfer Modal */}
            {transferModalOpen && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
                    <div className="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl">
                        <h3 className="text-lg font-bold text-foreground">Initiate Custody Transfer</h3>
                        <p className="text-sm text-muted-foreground mb-4">
                            Step 1 of 2: names a receiver. Custody moves only once they accept
                            (Chain of Custody page).
                        </p>

                        <div className="space-y-4">
                            <div>
                                <label className="text-sm font-medium">Recipient User ID (UUID)</label>
                                <input
                                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:ring-2 focus-visible:ring-ring"
                                    placeholder="e.g. f47ff616-80f2-4b54-b128-3b1b3fb6b8d0"
                                    value={targetUserId}
                                    onChange={e => setTargetUserId(e.target.value)}
                                />
                            </div>
                            <div>
                                <label className="text-sm font-medium">Reason for Transfer</label>
                                <textarea
                                    className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:ring-2 focus-visible:ring-ring"
                                    placeholder="e.g. Taking to forensic lab"
                                    value={transferReason}
                                    onChange={e => setTransferReason(e.target.value)}
                                />
                            </div>
                            {transferError && <p className="text-sm text-destructive">{transferError}</p>}
                        </div>

                        <div className="mt-6 flex justify-end gap-3">
                            <button
                                onClick={() => setTransferModalOpen(false)}
                                className="px-4 py-2 text-sm font-medium hover:bg-muted rounded-md"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleTransfer}
                                disabled={transferLoading || !targetUserId || !transferReason}
                                className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 disabled:opacity-50"
                            >
                                {transferLoading ? "Processing..." : "Confirm Transfer"}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Disposal Request Modal (B5, step 1 of 2) */}
            {disposalModalOpen && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
                    <div className="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-xl">
                        <h3 className="text-lg font-bold text-foreground">Request Disposal</h3>
                        <p className="text-sm text-muted-foreground mb-4">
                            Step 1 of 2: nothing is removed - status only changes once a JUDGE approves below.
                        </p>
                        <div className="space-y-4">
                            <textarea
                                className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:ring-2 focus-visible:ring-ring"
                                placeholder="Reason for disposal (mandatory)"
                                value={disposalReason}
                                onChange={e => setDisposalReason(e.target.value)}
                            />
                            {disposalError && <p className="text-sm text-destructive">{disposalError}</p>}
                        </div>
                        <div className="mt-6 flex justify-end gap-3">
                            <button
                                onClick={() => setDisposalModalOpen(false)}
                                className="px-4 py-2 text-sm font-medium hover:bg-muted rounded-md"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleRequestDisposal}
                                disabled={disposalLoading || !disposalReason}
                                className="px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground rounded-md hover:bg-destructive/90 disabled:opacity-50"
                            >
                                {disposalLoading ? "Submitting..." : "Submit Request"}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Header */}
            <div className="flex flex-col gap-4 border-b border-border pb-6 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex items-center gap-4">
                    <Link
                        href={`/dashboard/${userId}/evidence`}
                        className="rounded-full p-2 text-muted-foreground hover:bg-muted transition-colors"
                    >
                        <ArrowLeft className="h-5 w-5" />
                    </Link>
                    <div>
                        <h1 className="text-3xl font-bold text-foreground">{evidence.caseId}</h1>
                        <p className="flex items-center gap-2 text-sm text-muted-foreground">
                            <span className={cn(
                                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium capitalize",
                                evidence.evidenceType === "PHYSICAL" && "bg-blue-500/10 text-blue-500",
                                evidence.evidenceType === "DIGITAL" && "bg-purple-500/10 text-purple-500"
                            )}>
                                {evidence.evidenceType}
                            </span>
                            <span>•</span>
                            <span className={cn(
                                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium capitalize border",
                                evidence.status === "COLLECTED" && "border-blue-500/20 bg-blue-500/10 text-blue-400",
                                evidence.status === "PROCESSING" && "border-amber-500/20 bg-amber-500/10 text-amber-400",
                                evidence.status === "ANALYZED" && "border-purple-500/20 bg-purple-500/10 text-purple-400",
                                evidence.status === "RELEASED" && "border-green-500/20 bg-green-500/10 text-green-400",
                                evidence.status === "ARCHIVED" && "border-slate-500/20 bg-slate-500/10 text-slate-400",
                                evidence.status === "DISPOSED" && "border-red-500/20 bg-red-500/10 text-red-400"
                            )}>
                                {evidence.status}
                            </span>
                            <span>•</span>
                            <span className="text-xs">v{evidence.version}</span>
                        </p>
                    </div>
                </div>
                <div className="flex gap-3">
                    <button
                        onClick={() => setTransferModalOpen(true)}
                        disabled={evidence.status === "DISPOSED" || evidence.disposal?.state === "PENDING"}
                        className="px-4 py-2 text-sm font-medium border border-border rounded-md hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        Request Transfer
                    </button>
                    {evidence.disposal?.state !== "PENDING" && evidence.status !== "DISPOSED" && (
                        <button
                            onClick={() => setDisposalModalOpen(true)}
                            className="flex items-center gap-2 px-4 py-2 text-sm font-medium border border-destructive/30 text-destructive rounded-md hover:bg-destructive/10"
                        >
                            <Trash2 className="h-4 w-4" /> Request Disposal
                        </button>
                    )}
                    {evidence.evidenceType === "DIGITAL" && evidence.fileCid && (
                        <button
                            onClick={handleDownload}
                            className="flex items-center gap-2 px-4 py-2 text-sm font-medium border border-border rounded-md hover:bg-muted"
                        >
                            <Download className="h-4 w-4" /> Download File
                        </button>
                    )}
                    <button
                        onClick={handleVerify}
                        disabled={verifying}
                        className="flex items-center gap-2 px-4 py-2 text-sm font-medium border border-border rounded-md hover:bg-muted disabled:opacity-50"
                    >
                        {verifying ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}
                        Verify Integrity
                    </button>
                    <button
                        onClick={handleReport}
                        disabled={reportGenerating}
                        className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 disabled:opacity-50"
                    >
                        {reportGenerating ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                        Generate Report
                    </button>
                </div>
            </div>

            {/* Pending disposal decision (B5, step 2 of 2 - JUDGE only). Shown to every role so a
                non-Judge clicking Approve/Reject gets a real 403 back, same as every other
                authorization check in this app - not hidden by a client-side role guess. */}
            {evidence.disposal?.state === "PENDING" && (
                <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-6 space-y-3">
                    <h3 className="font-semibold text-foreground flex items-center gap-2">
                        <Trash2 className="h-4 w-4 text-amber-500" /> Disposal request pending
                    </h3>
                    <p className="text-sm text-muted-foreground">
                        Requested by <span className="font-mono">{evidence.disposal.requestedBy}</span>:
                        &quot;{evidence.disposal.reason}&quot;
                    </p>
                    <p className="text-xs text-muted-foreground">
                        You are logged in as {user?.role} - only JUDGE may decide this.
                    </p>
                    <input
                        className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                        placeholder="Decision note (mandatory)"
                        value={decisionNote}
                        onChange={e => setDecisionNote(e.target.value)}
                    />
                    {decisionError && <p className="text-sm text-destructive">{decisionError}</p>}
                    {decisionSuccess && <p className="text-sm text-green-500">{decisionSuccess}</p>}
                    <div className="flex gap-3">
                        <button
                            onClick={() => handleDisposalDecision("approve")}
                            disabled={decisionLoading !== null || !decisionNote.trim()}
                            className="px-4 py-2 text-sm font-medium bg-destructive text-destructive-foreground rounded-md hover:bg-destructive/90 disabled:opacity-50"
                        >
                            {decisionLoading === "approve" ? "Approving..." : "Approve (JUDGE only)"}
                        </button>
                        <button
                            onClick={() => handleDisposalDecision("reject")}
                            disabled={decisionLoading !== null || !decisionNote.trim()}
                            className="px-4 py-2 text-sm font-medium border border-border rounded-md hover:bg-muted disabled:opacity-50"
                        >
                            {decisionLoading === "reject" ? "Rejecting..." : "Reject (JUDGE only)"}
                        </button>
                    </div>
                </div>
            )}

            {downloadError && (
                <div className="rounded-md bg-destructive/10 border border-destructive/20 p-3 text-sm text-destructive">
                    {downloadError}
                </div>
            )}

            {reportError && (
                <div className="rounded-md bg-destructive/10 border border-destructive/20 p-3 text-sm text-destructive">
                    {reportError}
                </div>
            )}

            {verifyError && (
                <div className="rounded-md bg-destructive/10 border border-destructive/20 p-3 text-sm text-destructive">
                    {verifyError}
                </div>
            )}

            {verifyResult && (
                <div className={cn(
                    "rounded-lg border p-6 shadow-sm space-y-3",
                    verifyResult.status === "VERIFIED" && "border-green-500/30 bg-green-500/5",
                    verifyResult.status === "TAMPERED" && "border-destructive/30 bg-destructive/5",
                    verifyResult.status === "NOT_FOUND" && "border-amber-500/30 bg-amber-500/5"
                )}>
                    <h3 className="flex items-center gap-2 font-semibold text-foreground">
                        {verifyResult.status === "VERIFIED" && <ShieldCheck className="h-5 w-5 text-green-500" />}
                        {verifyResult.status === "TAMPERED" && <ShieldAlert className="h-5 w-5 text-destructive" />}
                        {verifyResult.status === "NOT_FOUND" && <ShieldQuestion className="h-5 w-5 text-amber-500" />}
                        Verification result: {verifyResult.status}
                        <span className="text-xs font-normal text-muted-foreground ml-auto">
                            checked {verifyResult.checkedAt ? new Date(verifyResult.checkedAt).toLocaleTimeString() : ""}
                        </span>
                    </h3>
                    {verifyResult.metadata && (
                        <div className="text-xs space-y-1">
                            <p className="text-muted-foreground">Metadata: <span className={verifyResult.metadata.result === "VERIFIED" ? "text-green-500" : "text-destructive"}>{verifyResult.metadata.result}</span></p>
                            <p className="font-mono text-foreground break-all">expected {verifyResult.metadata.expectedSha256}</p>
                            <p className="font-mono text-foreground break-all">actual &nbsp;&nbsp;{verifyResult.metadata.actualSha256}</p>
                        </div>
                    )}
                    {verifyResult.file && (
                        <div className="text-xs space-y-1 border-t border-border pt-3">
                            <p className="text-muted-foreground">File: <span className={verifyResult.file.result === "VERIFIED" ? "text-green-500" : "text-destructive"}>{verifyResult.file.result}</span></p>
                            <p className="font-mono text-foreground break-all">expected {verifyResult.file.expectedSha256}</p>
                            <p className="font-mono text-foreground break-all">actual &nbsp;&nbsp;{verifyResult.file.actualSha256}</p>
                        </div>
                    )}
                </div>
            )}

            <div className="grid gap-6 md:grid-cols-3">
                {/* Main Info */}
                <div className="md:col-span-2 space-y-6">
                    <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                        <h2 className="mb-4 flex items-center text-lg font-semibold">
                            <FileText className="mr-2 h-5 w-5 text-primary" />
                            Description & Context
                        </h2>
                        {evidence.metadataAvailable && evidence.metadata ? (
                            <p className="text-sm leading-relaxed text-muted-foreground whitespace-pre-wrap">
                                {evidence.metadata.description}
                            </p>
                        ) : (
                            <p className="text-sm text-muted-foreground italic">
                                Metadata unavailable (storage unreachable) - ledger information above is
                                unaffected (F1).
                            </p>
                        )}
                    </div>

                    {evidence.evidenceType === "DIGITAL" && evidence.metadata?.file && (
                        <div className="mb-6 rounded-lg border border-border bg-card p-6 shadow-sm">
                            <h3 className="mb-3 text-lg font-semibold text-foreground flex items-center gap-2">
                                <FileText className="h-5 w-5 text-primary" /> File
                            </h3>
                            <div className="flex items-center justify-between rounded-md border border-border bg-background p-3">
                                <div className="flex items-center gap-3 overflow-hidden">
                                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded bg-muted">
                                        <FileText className="h-5 w-5 text-muted-foreground" />
                                    </div>
                                    <div className="truncate">
                                        <p className="truncate text-sm font-medium text-foreground">
                                            {evidence.metadata.file.originalName}
                                        </p>
                                        <p className="text-xs text-muted-foreground">
                                            {(evidence.metadata.file.size / 1024).toFixed(1)} KB · encrypted at rest (F2/F3)
                                        </p>
                                    </div>
                                </div>
                                <button
                                    onClick={handleDownload}
                                    className="ml-2 rounded-md p-2 text-primary hover:bg-primary/10 transition-colors"
                                    title="Download (decrypts server-side)"
                                >
                                    <Download className="h-5 w-5" />
                                </button>
                            </div>
                        </div>
                    )}
                </div>

                {/* Sidebar Info */}
                <div className="space-y-6">
                    <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                        <h3 className="mb-4 font-semibold text-foreground">Metadata</h3>
                        <div className="space-y-4">
                            <div className="flex items-start gap-3">
                                <Calendar className="mt-0.5 h-4 w-4 text-muted-foreground" />
                                <div>
                                    <p className="text-xs font-medium text-muted-foreground">Collected Date</p>
                                    <p className="text-sm text-foreground">
                                        {evidence.metadata?.collectedAt
                                            ? new Date(evidence.metadata.collectedAt).toLocaleDateString()
                                            : "N/A"}
                                    </p>
                                </div>
                            </div>
                            <div className="flex items-start gap-3">
                                <MapPin className="mt-0.5 h-4 w-4 text-muted-foreground" />
                                <div>
                                    <p className="text-xs font-medium text-muted-foreground">Location Found</p>
                                    <p className="text-sm text-foreground">{evidence.metadata?.location || "N/A"}</p>
                                </div>
                            </div>
                            <div className="flex items-start gap-3">
                                <User className="mt-0.5 h-4 w-4 text-muted-foreground" />
                                <div>
                                    <p className="text-xs font-medium text-muted-foreground">Current Custodian</p>
                                    <p className="text-sm text-foreground font-mono">{evidence.currentCustodian}</p>
                                </div>
                            </div>
                            <div className="flex items-start gap-3">
                                <ShieldCheck className="mt-0.5 h-4 w-4 text-muted-foreground" />
                                <div>
                                    <p className="text-xs font-medium text-muted-foreground">Current Status</p>
                                    <p className="text-sm text-foreground">{evidence.status}</p>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                        <h3 className="mb-4 font-semibold text-foreground flex items-center gap-2">
                            <History className="h-4 w-4 text-primary" /> Ledger Record
                        </h3>
                        <div className="space-y-3 text-xs">
                            <div>
                                <p className="text-muted-foreground">Metadata SHA-256</p>
                                <p className="font-mono text-foreground break-all">{evidence.metadataSha256}</p>
                            </div>
                            {evidence.fileSha256 && (
                                <div>
                                    <p className="text-muted-foreground">File SHA-256</p>
                                    <p className="font-mono text-foreground break-all">{evidence.fileSha256}</p>
                                </div>
                            )}
                            <div>
                                <p className="text-muted-foreground">Last Action</p>
                                <p className="text-foreground">{evidence.lastAction} - &quot;{evidence.lastReason}&quot;</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Version history (C3) - real, from the ledger's own history, not the off-chain projection. */}
            <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                <h2 className="mb-4 flex items-center text-lg font-semibold">
                    <History className="mr-2 h-5 w-5 text-primary" />
                    Version History ({history.length})
                </h2>
                {historyLoading ? (
                    <p className="text-sm text-muted-foreground">Loading...</p>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="border-b border-border text-xs text-muted-foreground">
                                <tr>
                                    <th className="py-2 pr-4 font-medium">v</th>
                                    <th className="py-2 pr-4 font-medium">Action</th>
                                    <th className="py-2 pr-4 font-medium">Status</th>
                                    <th className="py-2 pr-4 font-medium">Actor</th>
                                    <th className="py-2 pr-4 font-medium">Reason</th>
                                    <th className="py-2 pr-4 font-medium">Timestamp</th>
                                    <th className="py-2 font-medium">Transaction ID</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {history.map(h => (
                                    <tr key={h.version}>
                                        <td className="py-2 pr-4 font-mono">{h.version}</td>
                                        <td className="py-2 pr-4">{h.action}</td>
                                        <td className="py-2 pr-4">{h.status}</td>
                                        <td className="py-2 pr-4 font-mono text-xs" title={h.actorId}>
                                            {h.actorId.substring(0, 8)}... ({h.actorRole})
                                        </td>
                                        <td className="py-2 pr-4 text-muted-foreground italic max-w-xs truncate" title={h.reason}>
                                            {h.reason || "-"}
                                        </td>
                                        <td className="py-2 pr-4 text-muted-foreground whitespace-nowrap">
                                            {new Date(h.timestamp).toLocaleString()}
                                        </td>
                                        <td className="py-2 font-mono text-xs text-muted-foreground break-all">{h.txId}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
}
