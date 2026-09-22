"use client";

import { useEffect, useState } from "react";
import api from "@/lib/api";
import { useParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Loader2, Pencil, Check, X, FileText, Users } from "lucide-react";

// Matches com.blockevidence.backend.dto.CaseResponse exactly. members/createdBy/leadOfficerId are
// plain user ids (no user-lookup endpoint, A4 was never built). No "crimeBoxes" - that concept has no
// backend equivalent (Part B). Adding/removing members is wired separately (Part C).
interface CaseDetail {
  id: string;
  caseNumber: string;
  title: string;
  description?: string;
  leadOfficerId: string;
  status: "OPEN" | "CLOSED";
  createdBy: string;
  createdAt: string;
  members: { userId: string; caseRole: string; addedBy: string; addedAt: string }[];
  evidenceIds: string[];
}

const STATUS_COLORS: Record<string, string> = {
  OPEN: "text-green-400 bg-green-400/10 border-green-400/20",
  CLOSED: "text-slate-400 bg-slate-400/10 border-slate-400/20",
};

export default function CaseDetailPage() {
  const params = useParams();
  const userId = params.userId as string;
  const caseId = params.caseId as string;

  const [caseData, setCaseData] = useState<CaseDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [editTitle, setEditTitle] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  useEffect(() => {
    api.get(`/api/cases/${caseId}`)
      .then(r => {
        setCaseData(r.data);
        setEditTitle(r.data.title);
        setEditDescription(r.data.description || "");
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [caseId]);

  const save = async () => {
    setSaving(true);
    setSaveError("");
    try {
      // UpdateCaseRequest has no "status" field - closing/reopening a case (E4) was never built, so
      // that control is not offered here rather than shown and silently doing nothing.
      const r = await api.put(`/api/cases/${caseId}`, {
        title: editTitle,
        description: editDescription || undefined,
      });
      setCaseData(prev => prev ? { ...prev, title: r.data.title, description: r.data.description } : null);
      setEditing(false);
    } catch (error: unknown) {
      const err = error as { response?: { data?: { message?: string } } };
      setSaveError(err.response?.data?.message || "Failed to save.");
    }
    finally { setSaving(false); }
  };

  if (loading) return <div className="flex items-center justify-center h-64"><Loader2 className="h-8 w-8 animate-spin text-primary" /></div>;
  if (!caseData) return <p className="text-muted-foreground text-center mt-12">Case not found.</p>;

  return (
    <div className="space-y-6 max-w-4xl">
      <Link href={`/dashboard/${userId}/cases`} className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors">
        <ArrowLeft className="h-4 w-4" /> Back to Cases
      </Link>

      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1 min-w-0">
          {editing ? (
            <div className="space-y-3">
              <input
                className="w-full text-2xl font-bold bg-transparent border-b border-primary focus:outline-none text-foreground"
                value={editTitle}
                onChange={e => setEditTitle(e.target.value)}
              />
              <textarea
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:border-primary"
                rows={2}
                placeholder="Description"
                value={editDescription}
                onChange={e => setEditDescription(e.target.value)}
              />
              {saveError && <p className="text-sm text-destructive">{saveError}</p>}
            </div>
          ) : (
            <>
              <div className="flex items-center gap-3 flex-wrap">
                <h1 className="text-2xl font-bold text-foreground">{caseData.caseNumber} - {caseData.title}</h1>
                <span className={`text-xs px-2 py-0.5 rounded-full border font-medium capitalize ${STATUS_COLORS[caseData.status] || STATUS_COLORS.OPEN}`}>
                  {caseData.status}
                </span>
              </div>
              {caseData.description && <p className="text-muted-foreground text-sm mt-1">{caseData.description}</p>}
              <p className="text-xs text-muted-foreground mt-2 font-mono">
                Lead officer: {caseData.leadOfficerId} · {new Date(caseData.createdAt).toLocaleDateString()}
              </p>
            </>
          )}
        </div>
        <div className="flex gap-2">
          {editing ? (
            <>
              <button onClick={() => setEditing(false)} className="p-2 rounded-lg border border-border text-muted-foreground hover:text-foreground"><X className="h-4 w-4" /></button>
              <button onClick={save} disabled={saving} className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50">
                {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />} Save
              </button>
            </>
          ) : (
            <button onClick={() => setEditing(true)} className="flex items-center gap-1.5 px-4 py-2 rounded-lg border border-border text-sm text-muted-foreground hover:text-foreground">
              <Pencil className="h-4 w-4" /> Edit
            </button>
          )}
        </div>
      </div>

      {/* Team */}
      <div className="rounded-xl border border-border bg-card p-5 space-y-4">
        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
          <Users className="h-4 w-4 text-primary" /> Team ({caseData.members.length})
        </h3>
        <div className="space-y-2">
          {caseData.members.map(m => (
            <div key={m.userId} className="flex items-center justify-between p-3 rounded-lg border border-border bg-background">
              <span className="text-sm font-mono text-foreground">{m.userId}</span>
              <span className="text-xs px-2 py-0.5 rounded-full border border-border text-muted-foreground">{m.caseRole}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Evidence linked to this case (E3) */}
      <div className="rounded-xl border border-border bg-card p-5 space-y-4">
        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
          <FileText className="h-4 w-4 text-primary" /> Evidence in this Case ({caseData.evidenceIds.length})
        </h3>
        {caseData.evidenceIds.length === 0 ? (
          <p className="text-sm text-muted-foreground">No evidence registered against this case yet.</p>
        ) : (
          <div className="space-y-2">
            {caseData.evidenceIds.map(id => (
              <Link
                key={id}
                href={`/dashboard/${userId}/evidence/${id}`}
                className="flex items-center justify-between p-3 rounded-lg border border-border bg-background hover:border-primary/30 transition-colors"
              >
                <span className="text-sm font-mono text-foreground">{id}</span>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
