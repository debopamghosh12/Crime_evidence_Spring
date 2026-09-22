"use client";

import { useEffect, useState, useCallback } from "react";
import api from "@/lib/api";
import { useParams } from "next/navigation";
import Link from "next/link";
import { Rss, RefreshCw } from "lucide-react";
import LottieLoader from "@/components/ui/LottieLoader";

// Matches com.blockevidence.backend.dto.ActivityEntryResponse exactly (GET /api/activity, H3).
// actorId/actorRole are the real, plain fields - no user-lookup endpoint exists to resolve a name
// (A4 was never built), so this shows a truncated id + role, not a fabricated display name.
interface ActivityEntry {
  txId: string;
  evidenceId: string;
  caseId: string;
  version: number;
  action: string;
  actorId: string;
  actorRole: string;
  reason: string;
  ledgerAt: string;
}

const ACTION_STYLES: Record<string, { color: string; dot: string }> = {
  CREATED: { color: "text-green-400", dot: "bg-green-400" },
  TRANSFER_INITIATED: { color: "text-amber-400", dot: "bg-amber-400" },
  TRANSFER_ACCEPTED: { color: "text-blue-400", dot: "bg-blue-400" },
  TRANSFER_REJECTED: { color: "text-destructive", dot: "bg-destructive" },
  TRANSFER_CANCELLED: { color: "text-muted-foreground", dot: "bg-muted-foreground" },
  STATUS_CHANGED: { color: "text-purple-400", dot: "bg-purple-400" },
  DISPOSAL_REQUESTED: { color: "text-amber-400", dot: "bg-amber-400" },
  DISPOSAL_APPROVED: { color: "text-destructive", dot: "bg-destructive" },
  DISPOSAL_REJECTED: { color: "text-muted-foreground", dot: "bg-muted-foreground" },
  METADATA_UPDATED: { color: "text-blue-400", dot: "bg-blue-400" },
};
const DEFAULT_STYLE = { color: "text-muted-foreground", dot: "bg-muted-foreground" };

function timeAgo(date: string) {
  const secs = Math.floor((Date.now() - new Date(date).getTime()) / 1000);
  if (secs < 60) return "just now";
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

export default function ActivityFeedPage() {
  const params = useParams();
  const userId = params.userId as string;
  const [logs, setLogs] = useState<ActivityEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const fetchActivity = useCallback(async (p = 0) => {
    setLoading(true);
    try {
      const r = await api.get("/api/activity", { params: { page: p, size: 20 } });
      setLogs(r.data.items || []);
      setTotalPages(r.data.totalPages || 1);
      setPage(p);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetchActivity(0); }, [fetchActivity]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground flex items-center gap-2">
            <Rss className="h-6 w-6 text-primary" /> Activity Feed
          </h1>
          <p className="text-sm text-muted-foreground mt-1">Every ledger event, newest first.</p>
        </div>
        <button
          onClick={() => fetchActivity(page)}
          className="flex items-center gap-2 px-3 py-2 rounded-lg border border-border text-sm text-muted-foreground hover:text-foreground hover:border-primary/30 transition-all"
        >
          <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
          Refresh
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center h-40">
          <LottieLoader size={120} />
        </div>
      ) : logs.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-56 text-muted-foreground space-y-3">
          <Rss className="h-12 w-12 opacity-30" />
          <p className="text-sm">No activity recorded yet.</p>
        </div>
      ) : (
        <div className="relative">
          <div className="absolute left-4 top-0 bottom-0 w-px bg-border" />
          <div className="space-y-1 pl-12">
            {logs.map((log) => {
              const style = ACTION_STYLES[log.action] || DEFAULT_STYLE;
              return (
                <div key={log.txId} className="relative group">
                  <span className={`absolute -left-[34px] top-3.5 w-2.5 h-2.5 rounded-full border-2 border-background ${style.dot}`} />
                  <Link
                    href={`/dashboard/${userId}/evidence/${log.evidenceId}`}
                    className="block py-3 px-4 rounded-lg hover:bg-muted/50 transition-colors"
                  >
                    <div className="flex items-baseline gap-2 flex-wrap">
                      <span className="text-sm font-mono text-foreground">{log.actorId.substring(0, 8)}...</span>
                      <span className={`text-sm ${style.color}`}>{log.action.replace(/_/g, " ").toLowerCase()}</span>
                      <span className="text-sm text-foreground font-medium">{log.caseId} (v{log.version})</span>
                    </div>
                    {log.reason && <p className="text-xs text-muted-foreground italic mt-0.5">&quot;{log.reason}&quot;</p>}
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-xs text-muted-foreground">{timeAgo(log.ledgerAt)}</span>
                      <span className="text-xs text-muted-foreground/50">·</span>
                      <span className="text-xs px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">{log.actorRole}</span>
                    </div>
                  </Link>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Pagination - PageResponse.page is 0-indexed */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-4">
          <button
            disabled={page <= 0}
            onClick={() => fetchActivity(page - 1)}
            className="px-3 py-1 rounded-lg border border-border text-sm text-muted-foreground disabled:opacity-40 hover:text-foreground"
          >Prev</button>
          <span className="text-sm text-muted-foreground">Page {page + 1} of {totalPages}</span>
          <button
            disabled={page >= totalPages - 1}
            onClick={() => fetchActivity(page + 1)}
            className="px-3 py-1 rounded-lg border border-border text-sm text-muted-foreground disabled:opacity-40 hover:text-foreground"
          >Next</button>
        </div>
      )}
    </div>
  );
}
