"use client";

import { useEffect, useState } from "react";
import axios from "axios";
import { useAuth } from "@/context/AuthContext";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { FolderOpen, ArrowLeft, Box, Loader2, Circle, ExternalLink, Pencil, Check, X } from "lucide-react";

interface CaseDetail {
  id: string;
  title: string;
  description?: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  createdBy: { fullName: string; username: string };
  crimeBoxes: { id: string; name: string; caseId: string; createdAt: string }[];
}

const STATUS_COLORS: Record<string, string> = {
  open: "text-green-400 bg-green-400/10 border-green-400/20",
  closed: "text-slate-400 bg-slate-400/10 border-slate-400/20",
  suspended: "text-amber-400 bg-amber-400/10 border-amber-400/20",
};

const STATUSES = ["open", "suspended", "closed"];

export default function CaseDetailPage() {
  const { token } = useAuth();
  const params = useParams();
  const router = useRouter();
  const userId = params.userId as string;
  const caseId = params.caseId as string;
  const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000";

  const [caseData, setCaseData] = useState<CaseDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [editTitle, setEditTitle] = useState("");
  const [editStatus, setEditStatus] = useState("open");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!token) return;
    axios.get(`${API}/api/v1/cases/${caseId}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => {
        setCaseData(r.data);
        setEditTitle(r.data.title);
        setEditStatus(r.data.status);
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [token, caseId]);

  const save = async () => {
    setSaving(true);
    try {
      const r = await axios.put(`${API}/api/v1/cases/${caseId}`,
        { title: editTitle, status: editStatus },
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setCaseData(prev => prev ? { ...prev, title: r.data.title, status: r.data.status } : null);
      setEditing(false);
    } catch (e) { console.error(e); }
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
              <select
                className="rounded-lg border border-border bg-background px-3 py-1.5 text-sm text-foreground"
                value={editStatus}
                onChange={e => setEditStatus(e.target.value)}
              >
                {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
          ) : (
            <>
              <div className="flex items-center gap-3 flex-wrap">
                <h1 className="text-2xl font-bold text-foreground">{caseData.title}</h1>
                <span className={`text-xs px-2 py-0.5 rounded-full border font-medium capitalize ${STATUS_COLORS[caseData.status] || STATUS_COLORS.open}`}>
                  {caseData.status}
                </span>
              </div>
              {caseData.description && <p className="text-muted-foreground text-sm mt-1">{caseData.description}</p>}
              <p className="text-xs text-muted-foreground mt-2">Created by {caseData.createdBy.fullName} · {new Date(caseData.createdAt).toLocaleDateString()}</p>
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

      {/* Crime Boxes */}
      <div className="rounded-xl border border-border bg-card p-5 space-y-4">
        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
          <Box className="h-4 w-4 text-primary" /> Crime Boxes in this Case
        </h3>
        {caseData.crimeBoxes.length === 0 ? (
          <p className="text-sm text-muted-foreground">No crime boxes linked yet. Use the Evidence page to link boxes.</p>
        ) : (
          <div className="space-y-2">
            {caseData.crimeBoxes.map(box => (
              <div key={box.id} className="flex items-center justify-between p-3 rounded-lg border border-border bg-background">
                <div>
                  <p className="text-sm font-medium text-foreground">{box.name}</p>
                  <p className="text-xs text-muted-foreground font-mono">{box.caseId}</p>
                </div>
                <Link href={`/dashboard/${userId}/evidence?caseId=${box.caseId}`} className="flex items-center gap-1 text-xs text-primary hover:underline">
                  View Evidence <ExternalLink className="h-3 w-3" />
                </Link>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
