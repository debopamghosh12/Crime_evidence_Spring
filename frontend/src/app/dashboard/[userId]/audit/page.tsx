"use client";

import { useEffect, useState, useCallback } from "react";
import api from "@/lib/api";
import { ShieldAlert, RefreshCw } from "lucide-react";
import LottieLoader from "@/components/ui/LottieLoader";

// Matches com.blockevidence.backend.dto.AuditLogResponse exactly (GET /api/audit, A6). Role: ADMIN or
// AUDITOR only (Permissions.READ_AUDIT_LOG) - narrower than ordinary evidence reads since this names
// users and IP addresses. Shown to every role; a non-ADMIN/AUDITOR gets a real 403 back, same principle
// as every other authorization check in this app.
interface AuditLogEntry {
    id: string;
    userId: string | null;
    email: string | null;
    action: string;
    resource: string;
    ipAddress: string | null;
    detail: string | null;
    occurredAt: string;
}

const ACTION_COLORS: Record<string, string> = {
    VIEW: "text-blue-400",
    DOWNLOAD: "text-purple-400",
    ACCESS_DENIED: "text-destructive",
    AUTH_FAILED: "text-destructive",
    TOKEN_USED_AFTER_DEACTIVATION: "text-amber-400",
};

export default function AuditLogPage() {
    const [logs, setLogs] = useState<AuditLogEntry[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);

    const fetchAudit = useCallback(async (p = 0) => {
        setLoading(true);
        setError("");
        try {
            const r = await api.get("/api/audit", { params: { page: p, size: 50 } });
            setLogs(r.data.items || []);
            setTotalPages(r.data.totalPages || 1);
            setPage(p);
        } catch (err: unknown) {
            const e = err as { response?: { status?: number; data?: { message?: string } } };
            if (e.response?.status === 403) {
                setError("403 Forbidden - the audit log is visible only to ADMIN or AUDITOR.");
            } else {
                setError(e.response?.data?.message || "Failed to load audit log.");
            }
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { fetchAudit(0); }, [fetchAudit]);

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-foreground flex items-center gap-2">
                        <ShieldAlert className="h-6 w-6 text-primary" /> Audit Log
                    </h1>
                    <p className="text-sm text-muted-foreground mt-1">
                        Every evidence VIEW/DOWNLOAD and every failed-auth/denied attempt, with user, resource, time and IP.
                    </p>
                </div>
                <button
                    onClick={() => fetchAudit(page)}
                    className="flex items-center gap-2 px-3 py-2 rounded-lg border border-border text-sm text-muted-foreground hover:text-foreground hover:border-primary/30 transition-all"
                >
                    <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
                    Refresh
                </button>
            </div>

            {error && (
                <div className="rounded-md bg-destructive/10 border border-destructive/20 p-3 text-sm text-destructive">
                    {error}
                </div>
            )}

            {loading ? (
                <div className="flex items-center justify-center h-40"><LottieLoader size={120} /></div>
            ) : logs.length > 0 ? (
                <>
                    <div className="overflow-x-auto rounded-lg border border-border">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-muted/50 border-b border-border text-xs text-muted-foreground uppercase">
                                <tr>
                                    <th className="px-4 py-3 font-medium">Time</th>
                                    <th className="px-4 py-3 font-medium">User</th>
                                    <th className="px-4 py-3 font-medium">Action</th>
                                    <th className="px-4 py-3 font-medium">Resource</th>
                                    <th className="px-4 py-3 font-medium">IP</th>
                                    <th className="px-4 py-3 font-medium">Detail</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {logs.map(log => (
                                    <tr key={log.id} className="hover:bg-muted/30">
                                        <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">
                                            {new Date(log.occurredAt).toLocaleString()}
                                        </td>
                                        <td className="px-4 py-3 font-mono text-xs">{log.email || log.userId || "-"}</td>
                                        <td className={`px-4 py-3 font-medium ${ACTION_COLORS[log.action] || "text-foreground"}`}>
                                            {log.action}
                                        </td>
                                        <td className="px-4 py-3 font-mono text-xs text-muted-foreground max-w-xs truncate" title={log.resource}>
                                            {log.resource}
                                        </td>
                                        <td className="px-4 py-3 text-muted-foreground">{log.ipAddress || "-"}</td>
                                        <td className="px-4 py-3 text-muted-foreground max-w-xs truncate" title={log.detail || ""}>
                                            {log.detail || "-"}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {totalPages > 1 && (
                        <div className="flex items-center justify-center gap-2 pt-4">
                            <button
                                disabled={page <= 0}
                                onClick={() => fetchAudit(page - 1)}
                                className="px-3 py-1 rounded-lg border border-border text-sm text-muted-foreground disabled:opacity-40 hover:text-foreground"
                            >Prev</button>
                            <span className="text-sm text-muted-foreground">Page {page + 1} of {totalPages}</span>
                            <button
                                disabled={page >= totalPages - 1}
                                onClick={() => fetchAudit(page + 1)}
                                className="px-3 py-1 rounded-lg border border-border text-sm text-muted-foreground disabled:opacity-40 hover:text-foreground"
                            >Next</button>
                        </div>
                    )}
                </>
            ) : !error && (
                <div className="flex flex-col items-center justify-center h-56 text-muted-foreground space-y-3">
                    <ShieldAlert className="h-12 w-12 opacity-30" />
                    <p className="text-sm">No audit entries yet.</p>
                </div>
            )}
        </div>
    );
}
