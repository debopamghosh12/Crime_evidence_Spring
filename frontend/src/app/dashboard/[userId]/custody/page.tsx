"use client";

import { useEffect, useState } from "react";
import api from "@/lib/api";
import Link from "next/link";
import {
    ArrowRightLeft,
    CheckCircle2,
    XCircle,
    Clock,
    User,
    Loader2
} from "lucide-react";
import { useParams } from "next/navigation";

// Matches com.blockevidence.backend.dto.PendingTransferResponse exactly (GET /api/transfers/pending).
// There is no separate "transfer id" - custody-transfer state lives on the evidence record itself
// (one pending transfer at a time), so accept/reject are keyed by evidenceId + expectedVersion, not a
// transfer id. fromUser is a plain user-id string (no user-lookup endpoint exists, A4 was never built).
interface PendingTransfer {
    evidenceId: string;
    caseId: string;
    status: string;
    version: number;
    fromUser: string;
    reason: string;
    notes: string | null;
    initiatedAt: string;
}

export default function CustodyDashboardPage() {
    const [incoming, setIncoming] = useState<PendingTransfer[]>([]);
    const [loading, setLoading] = useState(true);
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    // Native confirm()/alert()/prompt() block the whole page (and this session's own browser
    // automation) until dismissed - inline state instead, keyed by evidenceId.
    const [rejectingId, setRejectingId] = useState<string | null>(null);
    const [rejectNote, setRejectNote] = useState("");
    const [feedback, setFeedback] = useState<{ type: "success" | "error"; message: string } | null>(null);
    const params = useParams();
    const userId = params.userId as string;

    const fetchTransfers = async () => {
        try {
            // The backend exposes only "pending transfers waiting for ME to respond as receiver" -
            // there is no endpoint for "pending transfers I sent as sender" (an outgoing list), so
            // this page shows incoming only rather than fabricating an outgoing one.
            const response = await api.get("/api/transfers/pending");
            setIncoming(response.data);
        } catch (err) {
            console.error("Failed to fetch transfers", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchTransfers();
    }, []);

    const handleApprove = async (transfer: PendingTransfer) => {
        setActionLoading(transfer.evidenceId);
        setFeedback(null);
        try {
            await api.post(`/api/evidence/${transfer.evidenceId}/transfers/accept`, {
                expectedVersion: transfer.version,
            });
            await fetchTransfers(); // Refresh list
            setFeedback({ type: "success", message: `${transfer.caseId}: transfer accepted - custody has moved to you.` });
        } catch (error: unknown) {
            const err = error as { response?: { data?: { message?: string } } };
            setFeedback({ type: "error", message: err.response?.data?.message || "Failed to accept transfer." });
        } finally {
            setActionLoading(null);
        }
    };

    const submitReject = async (transfer: PendingTransfer) => {
        setActionLoading(transfer.evidenceId);
        setFeedback(null);
        try {
            await api.post(`/api/evidence/${transfer.evidenceId}/transfers/reject`, {
                expectedVersion: transfer.version,
                note: rejectNote || undefined,
            });
            setRejectingId(null);
            setRejectNote("");
            await fetchTransfers();
            setFeedback({ type: "success", message: `${transfer.caseId}: transfer rejected. Custody stays with the sender.` });
        } catch (error: unknown) {
            const err = error as { response?: { data?: { message?: string } } };
            setFeedback({ type: "error", message: err.response?.data?.message || "Failed to reject transfer." });
        } finally {
            setActionLoading(null);
        }
    };

    if (loading) {
        return (
            <div className="flex h-64 items-center justify-center">
                <Loader2 className="h-8 w-8 animate-spin text-primary" />
            </div>
        );
    }

    return (
        <div className="space-y-8 animate-in fade-in duration-500">
            <div>
                <h1 className="text-3xl font-bold text-foreground">Chain of Custody</h1>
                <p className="text-muted-foreground">
                    Transfers waiting for you to accept or reject as the named receiver (D2, step 2 of 2).
                </p>
            </div>

            {feedback && (
                <div className={`rounded-md p-3 text-sm ${feedback.type === "success"
                    ? "bg-primary/10 text-primary border border-primary/20"
                    : "bg-destructive/10 text-destructive border border-destructive/20"}`}>
                    {feedback.message}
                </div>
            )}

            <div className="space-y-4">
                <div className="flex items-center gap-2">
                    <div className="bg-blue-500/10 p-2 rounded-full">
                        <ArrowRightLeft className="h-5 w-5 text-blue-500" />
                    </div>
                    <h2 className="text-xl font-semibold">Incoming Requests</h2>
                    <span className="ml-auto rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
                        {incoming.length}
                    </span>
                </div>

                {incoming.length === 0 ? (
                    <div className="rounded-lg border border-dashed border-border p-8 text-center text-muted-foreground">
                        No incoming transfer requests.
                    </div>
                ) : (
                    <div className="grid gap-3 md:grid-cols-2">
                        {incoming.map((transfer) => (
                            <div key={transfer.evidenceId} className="rounded-lg border border-border bg-card p-4 shadow-sm transition-all hover:shadow-md">
                                <div className="flex items-start justify-between mb-3">
                                    <div className="space-y-1">
                                        <Link href={`/dashboard/${userId}/evidence/${transfer.evidenceId}`} className="font-semibold hover:underline flex items-center gap-2">
                                            {transfer.caseId}
                                            <span className="text-xs font-normal text-muted-foreground border border-border px-1.5 py-0.5 rounded">
                                                v{transfer.version}
                                            </span>
                                        </Link>
                                        <p className="text-sm text-muted-foreground">{transfer.status}</p>
                                    </div>
                                    <Clock className="h-4 w-4 text-yellow-500" />
                                </div>

                                <div className="flex items-center gap-3 text-sm text-muted-foreground mb-4 bg-muted/50 p-2 rounded-md">
                                    <User className="h-4 w-4" />
                                    <span className="font-mono text-xs">Sender: {transfer.fromUser}</span>
                                </div>

                                <p className="text-xs text-muted-foreground italic mb-4">&quot;{transfer.reason}&quot;</p>

                                {rejectingId === transfer.evidenceId ? (
                                    <div className="space-y-2">
                                        <input
                                            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                                            placeholder="Note (optional)"
                                            value={rejectNote}
                                            onChange={(e) => setRejectNote(e.target.value)}
                                        />
                                        <div className="flex gap-2">
                                            <button
                                                onClick={() => submitReject(transfer)}
                                                disabled={actionLoading === transfer.evidenceId}
                                                className="flex-1 inline-flex items-center justify-center gap-2 rounded-md bg-destructive px-3 py-2 text-sm font-medium text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50"
                                            >
                                                {actionLoading === transfer.evidenceId ? <Loader2 className="h-4 w-4 animate-spin" /> : "Confirm Reject"}
                                            </button>
                                            <button
                                                onClick={() => { setRejectingId(null); setRejectNote(""); }}
                                                className="px-3 py-2 text-sm font-medium hover:bg-muted rounded-md"
                                            >
                                                Cancel
                                            </button>
                                        </div>
                                    </div>
                                ) : (
                                    <div className="flex gap-2">
                                        <button
                                            onClick={() => handleApprove(transfer)}
                                            disabled={actionLoading === transfer.evidenceId}
                                            className="flex-1 inline-flex items-center justify-center gap-2 rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                                        >
                                            {actionLoading === transfer.evidenceId ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
                                            Accept
                                        </button>
                                        <button
                                            onClick={() => setRejectingId(transfer.evidenceId)}
                                            disabled={actionLoading === transfer.evidenceId}
                                            className="flex-1 inline-flex items-center justify-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm font-medium hover:bg-destructive/10 hover:text-destructive hover:border-destructive/50 disabled:opacity-50"
                                        >
                                            <XCircle className="h-4 w-4" />
                                            Reject
                                        </button>
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                )}
            </div>

            <p className="text-xs text-muted-foreground border-t border-border pt-4">
                Transfers you sent as the current custodian (outgoing) aren&apos;t listed here - the backend
                has no endpoint for that view yet, only for what&apos;s waiting on you to accept or reject.
                Check the evidence item itself for its transfer status.
            </p>
        </div>
    );
}
