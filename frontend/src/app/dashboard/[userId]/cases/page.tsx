"use client";

import { useEffect, useState } from "react";
import api from "@/lib/api";
import { useParams } from "next/navigation";
import Link from "next/link";
import { FolderOpen, Plus, ChevronRight, Loader2 } from "lucide-react";
import LottieLoader from "@/components/ui/LottieLoader";

// Matches com.blockevidence.backend.dto.CaseResponse exactly (GET /api/cases - a bare array, not
// wrapped). members/createdBy are plain user ids (no user-lookup endpoint, A4 was never built). There
// is no "crimeBoxes" concept on this backend (Part B - CrimeBox was cut entirely).
interface CaseSummary {
  id: string;
  caseNumber: string;
  title: string;
  description?: string;
  status: "OPEN" | "CLOSED";
  createdBy: string;
  createdAt: string;
  evidenceIds: string[];
}

const STATUS_COLORS: Record<string, string> = {
  OPEN: "text-green-400 bg-green-400/10 border-green-400/20",
  CLOSED: "text-slate-400 bg-slate-400/10 border-slate-400/20",
};

export default function CasesPage() {
  const params = useParams();
  const userId = params.userId as string;

  const [cases, setCases] = useState<CaseSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [showNew, setShowNew] = useState(false);
  const [newCaseNumber, setNewCaseNumber] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [newLeadOfficerId, setNewLeadOfficerId] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState("");

  useEffect(() => {
    api.get("/api/cases")
      .then(r => setCases(r.data))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  const createCase = async () => {
    if (!newCaseNumber.trim() || !newTitle.trim() || !newLeadOfficerId.trim()) return;
    setCreating(true);
    setCreateError("");
    try {
      // Role: ADMIN or PROSECUTOR only (Permissions.MANAGE_CASES) - a 403 for anyone else surfaces
      // inline below rather than being hidden by not showing this form at all.
      const r = await api.post("/api/cases", {
        caseNumber: newCaseNumber,
        title: newTitle,
        description: newDesc || undefined,
        leadOfficerId: newLeadOfficerId,
      });
      setCases(prev => [r.data, ...prev]);
      setShowNew(false);
      setNewCaseNumber("");
      setNewTitle("");
      setNewDesc("");
      setNewLeadOfficerId("");
    } catch (error: unknown) {
      const err = error as { response?: { data?: { message?: string } } };
      setCreateError(err.response?.data?.message || "Failed to create case.");
    }
    finally { setCreating(false); }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground flex items-center gap-2">
            <FolderOpen className="h-6 w-6 text-primary" /> Cases
          </h1>
          <p className="text-sm text-muted-foreground mt-1">Case files and their evidence.</p>
        </div>
        <button
          onClick={() => setShowNew(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors"
        >
          <Plus className="h-4 w-4" /> New Case
        </button>
      </div>

      {/* New Case Modal */}
      {showNew && (
        <div className="fixed inset-0 z-50 bg-background/80 backdrop-blur-sm flex items-center justify-center">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-md p-6 space-y-4">
            <h2 className="text-lg font-semibold text-foreground">New Case</h2>
            <p className="text-xs text-muted-foreground">Role: ADMIN or PROSECUTOR.</p>
            <div className="space-y-3">
              <input
                className="w-full rounded-lg border border-border bg-background px-4 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
                placeholder="Case number * (e.g. FE-TEST-002)"
                value={newCaseNumber}
                onChange={e => setNewCaseNumber(e.target.value)}
              />
              <input
                className="w-full rounded-lg border border-border bg-background px-4 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
                placeholder="Case title *"
                value={newTitle}
                onChange={e => setNewTitle(e.target.value)}
              />
              <textarea
                className="w-full rounded-lg border border-border bg-background px-4 py-2 text-sm text-foreground focus:outline-none focus:border-primary resize-none"
                rows={3}
                placeholder="Description (optional)"
                value={newDesc}
                onChange={e => setNewDesc(e.target.value)}
              />
              <input
                className="w-full rounded-lg border border-border bg-background px-4 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
                placeholder="Lead officer user ID (UUID) *"
                value={newLeadOfficerId}
                onChange={e => setNewLeadOfficerId(e.target.value)}
              />
              {createError && <p className="text-sm text-destructive">{createError}</p>}
            </div>
            <div className="flex gap-3 justify-end">
              <button onClick={() => setShowNew(false)} className="px-4 py-2 text-sm text-muted-foreground hover:text-foreground rounded-lg border border-border">Cancel</button>
              <button
                onClick={createCase}
                disabled={creating || !newCaseNumber.trim() || !newTitle.trim() || !newLeadOfficerId.trim()}
                className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50"
              >
                {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                Create
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Cases List */}
      {loading ? (
        <div className="flex items-center justify-center h-40">
          <LottieLoader size={120} />
        </div>
      ) : cases.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-64 text-muted-foreground space-y-3">
          <FolderOpen className="h-12 w-12 opacity-30" />
          <p className="text-sm">No cases yet.</p>
        </div>
      ) : (
        <div className="grid gap-4">
          {cases.map(c => (
            <Link
              key={c.id}
              href={`/dashboard/${userId}/cases/${c.id}`}
              className="group flex items-center justify-between p-5 rounded-xl border border-border bg-card hover:border-primary/30 hover:bg-primary/5 transition-all"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-3">
                  <h3 className="font-semibold text-foreground truncate">{c.caseNumber} - {c.title}</h3>
                  <span className={`text-xs px-2 py-0.5 rounded-full border font-medium capitalize ${STATUS_COLORS[c.status] || STATUS_COLORS.OPEN}`}>
                    {c.status}
                  </span>
                </div>
                {c.description && <p className="text-sm text-muted-foreground mt-1 truncate">{c.description}</p>}
                <p className="text-xs text-muted-foreground mt-2">
                  {c.evidenceIds.length} evidence item{c.evidenceIds.length !== 1 ? "s" : ""} · {new Date(c.createdAt).toLocaleDateString()}
                </p>
              </div>
              <ChevronRight className="h-5 w-5 text-muted-foreground group-hover:text-primary flex-shrink-0 ml-4 transition-colors" />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
