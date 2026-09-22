"use client";

import { useEffect, useState } from "react";
import axios from "axios";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
    ArrowLeft,
    MapPin,
    Calendar,
    User,
    ShieldCheck,
    FileText,
    History,
    Loader2
} from "lucide-react";
import { cn } from "@/lib/utils";

interface EvidenceDetail {
    id: string;
    caseId: string;
    type: string;
    description: string;
    status: string;
    collectionDate: string;
    location: string;
    collectedBy: { fullName: string };
    currentCustodian: { fullName: string };
    locked?: boolean;
    custodyEvents?: unknown[];
    files?: { id: string; fileName: string; fileSize: number; mimeType: string }[];
    accessLogs?: unknown[]; // Placeholder for now
}

export default function EvidenceDetailPage() {
    const params = useParams();
    const id = params.id as string;
    const userId = params.userId as string;
    const [evidence, setEvidence] = useState<EvidenceDetail | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    // Transfer Modal State
    const [transferModalOpen, setTransferModalOpen] = useState(false);
    const [transferReason, setTransferReason] = useState("");
    const [targetUserId, setTargetUserId] = useState("");
    const [transferLoading, setTransferLoading] = useState(false);

    const handleTransfer = async () => {
        if (!targetUserId || !transferReason) return;
        setTransferLoading(true);
        try {
            await axios.post(`/api/v1/custody/evidence/${id}/transfer`, {
                toUserId: targetUserId,
                reason: transferReason
            });
            setTransferModalOpen(false);
            // Refresh evidence
            const response = await axios.get(`/api/v1/evidence/${id}`);
            setEvidence(response.data.evidence);
            alert("Transfer initiated successfully!");
        } catch (error: unknown) {
            const err = error as { response?: { data?: { error?: string } } };
            alert(err.response?.data?.error || "Transfer failed");
        } finally {
            setTransferLoading(false);
        }
    };

    useEffect(() => {
        const fetchEvidence = async () => {
            try {
                const response = await axios.get(`/api/v1/evidence/${id}`);
                setEvidence(response.data.evidence);
            } catch {
                setError("Failed to load evidence details.");
            } finally {
                setLoading(false);
            }
        };

        if (id) {
            fetchEvidence();
        }
    }, [id]);

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
                            Transfer this evidence to another officer or custodian.
                        </p>

                        <div className="space-y-4">
                            <div>
                                <label className="text-sm font-medium">Recipient User ID or Username</label>
                                <input
                                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:ring-2 focus-visible:ring-ring"
                                    placeholder="Enter Username or ID"
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
                                disabled={transferLoading || !targetUserId}
                                className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 disabled:opacity-50"
                            >
                                {transferLoading ? "Processing..." : "Confirm Transfer"}
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
                                evidence.type.toLowerCase() === "physical" && "bg-blue-500/10 text-blue-500",
                                evidence.type.toLowerCase() === "digital" && "bg-purple-500/10 text-purple-500",
                                evidence.type.toLowerCase() === "testimonial" && "bg-green-500/10 text-green-500"
                            )}>
                                {evidence.type}
                            </span>
                            <span>•</span>
                            <span className={cn(
                                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium capitalize border",
                                evidence.status.toLowerCase() === "collected" && "border-blue-500/20 bg-blue-500/10 text-blue-400",
                                evidence.status.toLowerCase() === "in-custody" && "border-amber-500/20 bg-amber-500/10 text-amber-400",
                                evidence.status.toLowerCase() === "analyzed" && "border-purple-500/20 bg-purple-500/10 text-purple-400",
                                evidence.status.toLowerCase() === "secured" && "border-green-500/20 bg-green-500/10 text-green-400",
                                evidence.status.toLowerCase() === "archived" && "border-slate-500/20 bg-slate-500/10 text-slate-400"
                            )}>
                                {evidence.status}
                            </span>
                        </p>
                    </div>
                </div>
                <div className="flex gap-3">
                    {/* Action Buttons */}
                    <button
                        onClick={() => setTransferModalOpen(true)}
                        disabled={!!evidence.locked}
                        className="px-4 py-2 text-sm font-medium border border-border rounded-md hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {evidence.locked ? "Transfer Pending" : "Request Transfer"}
                    </button>
                    <button className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90">
                        Generate Report
                    </button>
                </div>
            </div>

            <div className="grid gap-6 md:grid-cols-3">
                {/* Main Info */}
                <div className="md:col-span-2 space-y-6">
                    <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                        <h2 className="mb-4 flex items-center text-lg font-semibold">
                            <FileText className="mr-2 h-5 w-5 text-primary" />
                            Description & Context
                        </h2>
                        <p className="text-sm leading-relaxed text-muted-foreground whitespace-pre-wrap">
                            {evidence.description}
                        </p>
                    </div>

                    {/* Attachments Section */}
                    {evidence.files && evidence.files.length > 0 && (
                        <div className="mb-6 rounded-lg border border-border bg-card p-6 shadow-sm">
                            <h3 className="mb-3 text-lg font-semibold text-foreground flex items-center gap-2">
                                <FileText className="h-5 w-5 text-primary" /> Attached Files
                            </h3>
                            <div className="grid gap-2 md:grid-cols-2">
                                {evidence.files.map((file) => (
                                    <div key={file.id} className="flex items-center justify-between rounded-md border border-border bg-background p-3">
                                        <div className="flex items-center gap-3 overflow-hidden">
                                            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded bg-muted">
                                                <FileText className="h-5 w-5 text-muted-foreground" />
                                            </div>
                                            <div className="truncate">
                                                <p className="truncate text-sm font-medium text-foreground">
                                                    {file.fileName}
                                                </p>
                                                <p className="text-xs text-muted-foreground">
                                                    {(file.fileSize / 1024).toFixed(1)} KB
                                                </p>
                                            </div>
                                        </div>
                                        <a
                                            href={`/api/v1/evidence/${evidence.id}/files/${file.id}/download`}
                                            target="_blank"
                                            rel="noreferrer"
                                            className="ml-2 rounded-md p-2 text-primary hover:bg-primary/10 transition-colors"
                                            title="Download"
                                        >
                                            <ArrowLeft className="h-5 w-5 rotate-[-90deg]" />
                                        </a>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                        <h2 className="mb-4 flex items-center text-lg font-semibold">
                            <History className="mr-2 h-5 w-5 text-primary" />
                            Chain of Custody Timeline
                        </h2>
                        <div className="border-l-2 border-border ml-2 space-y-6 pl-6 relative">
                            {/* Collection Event (Always first) */}
                            <div className="relative">
                                <span className="absolute -left-[31px] flex h-4 w-4 items-center justify-center rounded-full bg-muted-foreground ring-4 ring-background"></span>
                                <p className="text-sm font-medium text-foreground">Collection</p>
                                <p className="text-xs text-muted-foreground">
                                    Collected by {evidence.collectedBy?.fullName} on {new Date(evidence.collectionDate).toLocaleDateString()}
                                </p>
                            </div>

                            {/* Custody Events */}
                            {evidence.custodyEvents?.map((event: any) => ( // eslint-disable-line @typescript-eslint/no-explicit-any
                                <div key={event.id} className="relative">
                                    <span className={cn(
                                        "absolute -left-[31px] flex h-4 w-4 items-center justify-center rounded-full ring-4 ring-background",
                                        event.status === 'pending' ? "bg-yellow-500" : "bg-blue-500"
                                    )}></span>
                                    <p className="text-sm font-medium text-foreground capitalize">{event.eventType} ({event.status})</p>
                                    <p className="text-xs text-muted-foreground">
                                        From: {event.fromUser?.fullName} → To: {event.toUser?.fullName}
                                    </p>
                                    <p className="text-xs text-muted-foreground italic mt-0.5">&quot;{event.reason}&quot;</p>
                                    <p className="text-xs text-muted-foreground mt-1">
                                        {new Date(event.timestamp).toLocaleString()}
                                    </p>
                                </div>
                            ))}

                            {/* Current Status */}
                            <div className="relative">
                                <span className="absolute -left-[31px] flex h-4 w-4 items-center justify-center rounded-full bg-green-500 ring-4 ring-background"></span>
                                <p className="text-sm font-medium text-foreground">Current Custodian</p>
                                <p className="text-xs text-muted-foreground">
                                    {evidence.currentCustodian?.fullName}
                                </p>
                            </div>
                        </div>
                    </div>
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
                                        {new Date(evidence.collectionDate).toLocaleDateString()}
                                    </p>
                                </div>
                            </div>
                            <div className="flex items-start gap-3">
                                <MapPin className="mt-0.5 h-4 w-4 text-muted-foreground" />
                                <div>
                                    <p className="text-xs font-medium text-muted-foreground">Location Found</p>
                                    <p className="text-sm text-foreground">{evidence.location || "N/A"}</p>
                                </div>
                            </div>
                            <div className="flex items-start gap-3">
                                <User className="mt-0.5 h-4 w-4 text-muted-foreground" />
                                <div>
                                    <p className="text-xs font-medium text-muted-foreground">Collected By</p>
                                    <p className="text-sm text-foreground">{evidence.collectedBy?.fullName || "Unknown"}</p>
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

                    {/* QR Code Panel */}
                    <QRPanel evidenceId={id} />
                </div>
            </div>

            {/* ── Extra Panels (full width below grid) ── */}
            <CommentsPanel evidenceId={id} />
            <LabResultsPanel evidenceId={id} />
            <AccessRequestPanel evidenceId={id} />
        </div>
    );
}

// ─── QR Code Panel ────────────────────────────────────────────────
function QRPanel({ evidenceId }: { evidenceId: string }) {
    const [qrData, setQrData] = useState<{ qrDataUrl: string; verifyUrl: string } | null>(null);
    const [loading, setLoading] = useState(false);
    const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000";
    const token = typeof window !== "undefined" ? localStorage.getItem("token") : null;

    const generate = async () => {
        setLoading(true);
        try {
            const r = await axios.get(`${API}/api/v1/evidence/${evidenceId}/qr`, {
                headers: { Authorization: `Bearer ${token}` },
            });
            setQrData(r.data);
        } catch (e) { console.error(e); }
        finally { setLoading(false); }
    };

    return (
        <div className="rounded-lg border border-border bg-card p-5 shadow-sm space-y-3">
            <h3 className="font-semibold text-foreground text-sm flex items-center gap-2">
                <ShieldCheck className="h-4 w-4 text-primary" /> Verification QR
            </h3>
            {qrData ? (
                <div className="space-y-2">
                    <img src={qrData.qrDataUrl} alt="QR Code" className="w-32 h-32 rounded-lg" />
                    <p className="text-xs text-muted-foreground break-all">{qrData.verifyUrl}</p>
                    <a href={qrData.qrDataUrl} download="evidence-qr.png" className="text-xs text-primary hover:underline">Download QR</a>
                </div>
            ) : (
                <button
                    onClick={generate}
                    disabled={loading}
                    className="flex items-center gap-2 px-3 py-1.5 rounded-lg border border-border text-xs text-muted-foreground hover:text-foreground hover:border-primary/30 transition-all disabled:opacity-50"
                >
                    {loading ? <Loader2 className="h-3 w-3 animate-spin" /> : <ShieldCheck className="h-3 w-3" />}
                    Generate QR Code
                </button>
            )}
        </div>
    );
}

// ─── Comments Panel ───────────────────────────────────────────────
function CommentsPanel({ evidenceId }: { evidenceId: string }) {
    const [comments, setComments] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);
    const [content, setContent] = useState("");
    const [posting, setPosting] = useState(false);
    const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000";
    const token = typeof window !== "undefined" ? localStorage.getItem("token") : null;

    useEffect(() => {
        axios.get(`${API}/api/v1/evidence/${evidenceId}/comments`, {
            headers: { Authorization: `Bearer ${token}` },
        }).then(r => setComments(r.data)).catch(console.error).finally(() => setLoading(false));
    }, [evidenceId]);

    const post = async () => {
        if (!content.trim()) return;
        setPosting(true);
        try {
            const r = await axios.post(`${API}/api/v1/evidence/${evidenceId}/comments`, { content }, {
                headers: { Authorization: `Bearer ${token}` },
            });
            setComments(prev => [...prev, r.data]);
            setContent("");
        } catch (e) { console.error(e); }
        finally { setPosting(false); }
    };

    return (
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm space-y-4">
            <h3 className="font-semibold text-foreground flex items-center gap-2">
                <FileText className="h-4 w-4 text-primary" /> Officer Comments
                <span className="text-xs text-muted-foreground font-normal">({comments.length})</span>
            </h3>
            {loading ? <Loader2 className="h-4 w-4 animate-spin text-primary" /> : comments.length === 0 ? (
                <p className="text-sm text-muted-foreground">No comments yet.</p>
            ) : (
                <div className="space-y-3">
                    {comments.map((c: any) => (
                        <div key={c.id} className="rounded-lg border border-border bg-background p-3 space-y-1">
                            <div className="flex items-center justify-between">
                                <span className="text-xs font-semibold text-foreground">{c.user?.fullName || c.user?.username}</span>
                                <span className="text-xs text-muted-foreground">{new Date(c.createdAt).toLocaleString()}</span>
                            </div>
                            <p className="text-sm text-foreground">{c.content}</p>
                        </div>
                    ))}
                </div>
            )}
            <div className="flex gap-2">
                <textarea
                    className="flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary resize-none"
                    rows={2}
                    placeholder="Add a comment…"
                    value={content}
                    onChange={e => setContent(e.target.value)}
                />
                <button
                    onClick={post}
                    disabled={posting || !content.trim()}
                    className="px-4 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50 self-end py-2"
                >
                    {posting ? <Loader2 className="h-4 w-4 animate-spin" /> : "Post"}
                </button>
            </div>
        </div>
    );
}

// ─── Lab Results Panel ────────────────────────────────────────────
function LabResultsPanel({ evidenceId }: { evidenceId: string }) {
    const [results, setResults] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const [form, setForm] = useState({ title: "", summary: "", findings: "" });
    const [posting, setPosting] = useState(false);
    const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000";
    const token = typeof window !== "undefined" ? localStorage.getItem("token") : null;

    useEffect(() => {
        axios.get(`${API}/api/v1/evidence/${evidenceId}/lab-results`, {
            headers: { Authorization: `Bearer ${token}` },
        }).then(r => setResults(r.data)).catch(console.error).finally(() => setLoading(false));
    }, [evidenceId]);

    const submit = async () => {
        if (!form.title || !form.summary) return;
        setPosting(true);
        try {
            const r = await axios.post(`${API}/api/v1/evidence/${evidenceId}/lab-results`, form, {
                headers: { Authorization: `Bearer ${token}` },
            });
            setResults(prev => [r.data, ...prev]);
            setShowForm(false);
            setForm({ title: "", summary: "", findings: "" });
        } catch (e) { console.error(e); }
        finally { setPosting(false); }
    };

    return (
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm space-y-4">
            <div className="flex items-center justify-between">
                <h3 className="font-semibold text-foreground flex items-center gap-2">
                    <History className="h-4 w-4 text-primary" /> Lab Results
                    <span className="text-xs text-muted-foreground font-normal">({results.length})</span>
                </h3>
                <button onClick={() => setShowForm(s => !s)} className="text-xs text-primary hover:underline">
                    {showForm ? "Cancel" : "+ Submit Result"}
                </button>
            </div>
            {showForm && (
                <div className="rounded-lg border border-border bg-background p-4 space-y-3">
                    <input className="w-full rounded-lg border border-border bg-card px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary" placeholder="Title *" value={form.title} onChange={e => setForm(f => ({ ...f, title: e.target.value }))} />
                    <textarea className="w-full rounded-lg border border-border bg-card px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary resize-none" rows={2} placeholder="Summary *" value={form.summary} onChange={e => setForm(f => ({ ...f, summary: e.target.value }))} />
                    <textarea className="w-full rounded-lg border border-border bg-card px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary resize-none" rows={3} placeholder="Detailed findings (optional)" value={form.findings} onChange={e => setForm(f => ({ ...f, findings: e.target.value }))} />
                    <button onClick={submit} disabled={posting || !form.title || !form.summary} className="px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50 flex items-center gap-2">
                        {posting ? <Loader2 className="h-4 w-4 animate-spin" /> : null} Submit
                    </button>
                </div>
            )}
            {loading ? <Loader2 className="h-4 w-4 animate-spin text-primary" /> : results.length === 0 && !showForm ? (
                <p className="text-sm text-muted-foreground">No lab results submitted yet.</p>
            ) : (
                <div className="space-y-3">
                    {results.map((r: any) => (
                        <div key={r.id} className="rounded-lg border border-border bg-background p-4 space-y-2">
                            <div className="flex items-center justify-between">
                                <span className="text-sm font-semibold text-foreground">{r.title}</span>
                                <span className="text-xs text-muted-foreground">{new Date(r.submittedAt).toLocaleDateString()}</span>
                            </div>
                            <p className="text-sm text-foreground">{r.summary}</p>
                            {r.findings && <p className="text-xs text-muted-foreground border-t border-border pt-2 mt-2">{r.findings}</p>}
                            <p className="text-xs text-muted-foreground">By {r.submittedBy?.fullName}</p>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

// ─── Access Request Panel ─────────────────────────────────────────
function AccessRequestPanel({ evidenceId }: { evidenceId: string }) {
    const [requests, setRequests] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);
    const [reason, setReason] = useState("");
    const [posting, setPosting] = useState(false);
    const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000";
    const token = typeof window !== "undefined" ? localStorage.getItem("token") : null;

    useEffect(() => {
        axios.get(`${API}/api/v1/evidence/${evidenceId}/requests`, {
            headers: { Authorization: `Bearer ${token}` },
        }).then(r => setRequests(r.data)).catch(console.error).finally(() => setLoading(false));
    }, [evidenceId]);

    const submitsRequest = async () => {
        if (!reason.trim()) return;
        setPosting(true);
        try {
            const r = await axios.post(`${API}/api/v1/evidence/${evidenceId}/requests`, { reason }, {
                headers: { Authorization: `Bearer ${token}` },
            });
            setRequests(prev => [r.data, ...prev]);
            setReason("");
        } catch (e: any) {
            alert(e.response?.data?.error || "Request failed");
        }
        finally { setPosting(false); }
    };

    const STATUS_BADGE: Record<string, string> = {
        pending: "text-amber-400 bg-amber-400/10 border-amber-400/20",
        approved: "text-green-400 bg-green-400/10 border-green-400/20",
        denied: "text-red-400 bg-red-400/10 border-red-400/20",
    };

    return (
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm space-y-4">
            <h3 className="font-semibold text-foreground flex items-center gap-2">
                <ShieldCheck className="h-4 w-4 text-primary" /> Access Requests
                <span className="text-xs text-muted-foreground font-normal">({requests.length})</span>
            </h3>
            {requests.length > 0 && (
                <div className="space-y-2">
                    {requests.map((r: any) => (
                        <div key={r.id} className="rounded-lg border border-border bg-background p-4 space-y-1">
                            <div className="flex items-center justify-between">
                                <span className="text-sm font-medium text-foreground">{r.requester?.fullName}</span>
                                <span className={`text-xs px-2 py-0.5 rounded-full border capitalize ${STATUS_BADGE[r.status] || ""}`}>{r.status}</span>
                            </div>
                            <p className="text-sm text-muted-foreground">{r.reason}</p>
                            {r.reviewNotes && <p className="text-xs text-muted-foreground italic border-t border-border pt-1 mt-1">"{r.reviewNotes}"</p>}
                        </div>
                    ))}
                </div>
            )}
            {!loading && (
                <div className="flex gap-2">
                    <input
                        className="flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
                        placeholder="Reason for requesting access…"
                        value={reason}
                        onChange={e => setReason(e.target.value)}
                    />
                    <button
                        onClick={submitsRequest}
                        disabled={posting || !reason.trim()}
                        className="px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50"
                    >
                        {posting ? <Loader2 className="h-4 w-4 animate-spin" /> : "Request"}
                    </button>
                </div>
            )}
        </div>
    );
}

