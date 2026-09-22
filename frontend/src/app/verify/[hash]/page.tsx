"use client";

import { useEffect, useState } from "react";
import axios from "axios";
import { useParams } from "next/navigation";
import { ShieldCheck, ShieldOff, Loader2, FileText, User, Calendar, MapPin, Hash } from "lucide-react";

interface VerifyResult {
  verified: boolean;
  message?: string;
  evidence?: {
    id: string;
    caseId: string;
    type: string;
    description: string;
    collectionDate: string;
    location: string;
    status: string;
    createdAt: string;
    collectedBy: { fullName: string; badgeNumber?: string; department?: string };
    files: { fileName: string; sha256Hash: string; uploadedAt: string; fileSize: number }[];
  };
}

export default function PublicVerifyPage() {
  const params = useParams();
  const hash = params.hash as string;
  const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:3000";

  const [result, setResult] = useState<VerifyResult | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!hash) return;
    axios.get(`${API}/api/v1/verify/${hash}`)
      .then(r => setResult(r.data))
      .catch(err => {
        if (err.response?.status === 404) setResult({ verified: false, message: "No evidence found matching this hash." });
        else setResult({ verified: false, message: "Verification service unavailable." });
      })
      .finally(() => setLoading(false));
  }, [hash]);

  return (
    <div className="min-h-screen bg-[#0c0f14] text-foreground flex items-center justify-center p-6">
      <div className="w-full max-w-2xl space-y-8">
        {/* Header */}
        <div className="text-center space-y-2">
          <h1 className="text-3xl font-bold">
            <span className="text-white">Block</span>
            <span className="text-primary">Evidence</span>
          </h1>
          <p className="text-muted-foreground text-sm">Evidence Integrity Verification</p>
        </div>

        {loading ? (
          <div className="flex flex-col items-center gap-4 py-16">
            <Loader2 className="h-10 w-10 animate-spin text-primary" />
            <p className="text-muted-foreground text-sm">Verifying hash against records…</p>
          </div>
        ) : !result ? null : result.verified && result.evidence ? (
          <div className="space-y-6">
            {/* Verified Banner */}
            <div className="flex items-center gap-4 p-6 rounded-2xl bg-green-500/10 border border-green-500/30">
              <ShieldCheck className="h-12 w-12 text-green-400 shrink-0" />
              <div>
                <p className="text-xl font-bold text-green-400">Verified ✓</p>
                <p className="text-sm text-green-300/70 mt-0.5">This evidence record exists and has not been tampered with.</p>
              </div>
            </div>

            {/* Hash */}
            <div className="rounded-xl border border-border bg-card p-4 space-y-1">
              <p className="text-xs text-muted-foreground flex items-center gap-1"><Hash className="h-3 w-3" /> Queried Hash</p>
              <code className="text-xs text-primary font-mono break-all">{hash}</code>
            </div>

            {/* Evidence Details */}
            <div className="rounded-xl border border-border bg-card divide-y divide-border">
              {[
                { icon: FileText, label: "Type", value: result.evidence.type },
                { icon: FileText, label: "Description", value: result.evidence.description },
                { icon: MapPin, label: "Location", value: result.evidence.location },
                { icon: Calendar, label: "Collection Date", value: new Date(result.evidence.collectionDate).toLocaleDateString() },
                { icon: User, label: "Collected By", value: `${result.evidence.collectedBy.fullName}${result.evidence.collectedBy.badgeNumber ? ` · Badge #${result.evidence.collectedBy.badgeNumber}` : ""}${result.evidence.collectedBy.department ? ` · ${result.evidence.collectedBy.department}` : ""}` },
                { icon: FileText, label: "Case ID", value: result.evidence.caseId },
                { icon: FileText, label: "Status", value: result.evidence.status },
                { icon: Calendar, label: "Registered On", value: new Date(result.evidence.createdAt).toLocaleString() },
              ].map(({ icon: Icon, label, value }) => (
                <div key={label} className="flex items-start gap-3 px-5 py-3">
                  <Icon className="h-4 w-4 text-muted-foreground mt-0.5 shrink-0" />
                  <div>
                    <p className="text-xs text-muted-foreground">{label}</p>
                    <p className="text-sm text-foreground font-medium">{value}</p>
                  </div>
                </div>
              ))}
            </div>

            {/* Files */}
            {result.evidence.files.length > 0 && (
              <div className="rounded-xl border border-border bg-card p-4 space-y-3">
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">Attached Files</p>
                {result.evidence.files.map(f => (
                  <div key={f.sha256Hash} className="space-y-1">
                    <p className="text-sm text-foreground font-medium">{f.fileName} <span className="text-muted-foreground text-xs">({(f.fileSize / 1024).toFixed(1)} KB)</span></p>
                    <code className="text-xs text-primary/70 font-mono break-all block">{f.sha256Hash}</code>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="flex flex-col items-center gap-4 py-12">
            <div className="flex items-center gap-4 p-6 rounded-2xl bg-red-500/10 border border-red-500/30 w-full">
              <ShieldOff className="h-12 w-12 text-red-400 shrink-0" />
              <div>
                <p className="text-xl font-bold text-red-400">Not Verified ✗</p>
                <p className="text-sm text-red-300/70 mt-0.5">{result.message}</p>
              </div>
            </div>
            <div className="rounded-xl border border-border bg-card p-4 w-full">
              <p className="text-xs text-muted-foreground flex items-center gap-1"><Hash className="h-3 w-3" /> Queried Hash</p>
              <code className="text-xs text-red-400 font-mono break-all">{hash}</code>
            </div>
          </div>
        )}

        <p className="text-center text-xs text-muted-foreground/50">BlockEvidence Public Verification Portal — No login required</p>
      </div>
    </div>
  );
}
