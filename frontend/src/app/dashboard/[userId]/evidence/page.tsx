"use client";

import { useEffect, useState } from "react";
import api from "@/lib/api";
import Link from "next/link";
import { Plus, Search, FileText, ArrowRight } from "lucide-react";
import LottieLoader from "@/components/ui/LottieLoader";
import { cn } from "@/lib/utils";
import { useParams } from "next/navigation";

// Matches com.blockevidence.backend.dto.EvidenceSearchResult exactly (GET /api/evidence/search) -
// currentCustodian/createdBy are plain user-id strings on the real backend, not nested {fullName}
// objects (there is no user-lookup endpoint to resolve a name from an id, A4 was never built).
interface EvidenceSearchResult {
    evidenceId: string;
    caseId: string;
    evidenceType: string;
    status: string;
    version: number;
    currentCustodian: string;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
    lastAction: string;
    lastReason: string;
}

export default function EvidenceListPage() {
    const [evidence, setEvidence] = useState<EvidenceSearchResult[]>([]);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");
    const [pagination, setPagination] = useState({ page: 0, totalPages: 1 });
    const params = useParams();
    const userId = params.userId as string;

    useEffect(() => {
        const fetchEvidence = async () => {
            setLoading(true);
            try {
                const response = await api.get("/api/evidence/search", {
                    params: {
                        q: searchTerm || undefined,
                        page: pagination.page,
                        size: 10,
                    },
                });
                setEvidence(response.data.items);
                setPagination({ page: response.data.page, totalPages: response.data.totalPages || 1 });
            } catch (error) {
                console.error("Failed to fetch evidence:", error);
            } finally {
                setLoading(false);
            }
        };

        const timer = setTimeout(() => {
            fetchEvidence();
        }, 500); // Debounce search
        return () => clearTimeout(timer);
    }, [searchTerm, pagination.page]);

    return (
        <div className="space-y-8">
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b border-border pb-6">
                <div>
                    <h1 className="text-2xl font-bold text-foreground">Evidence</h1>
                    <p className="text-muted-foreground text-sm mt-1 mb-2">
                        View and manage all evidence records.
                    </p>
                </div>
                <Link
                    href={`/dashboard/${userId}/evidence/new`}
                    className="flex items-center justify-center bg-primary text-black px-6 py-2 text-sm font-bold uppercase tracking-wide hover:bg-primary/90 transition-all"
                >
                    <Plus className="mr-2 h-4 w-4" />
                    Log New Item
                </Link>
            </div>

            <div className="flex items-center rounded-lg border border-border bg-card px-4 py-3 focus-within:border-primary focus-within:ring-1 focus-within:ring-primary transition-all">
                <Search className="mr-3 h-4 w-4 text-muted-foreground" />
                <input
                    type="text"
                    placeholder="Search by case number or reason (current version only)..."
                    className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                />
            </div>

            <div className="rounded-lg border border-border bg-card overflow-hidden">
                {loading ? (
                    <div className="flex h-40 items-center justify-center text-muted-foreground">
                        <LottieLoader size={120} />
                    </div>
                ) : evidence.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
                        <FileText className="mb-4 h-12 w-12 opacity-20" />
                        <p>No evidence found.</p>
                    </div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-muted/50 border-b border-border text-xs text-muted-foreground uppercase">
                                <tr>
                                    <th className="px-6 py-3 font-medium">Case</th>
                                    <th className="px-6 py-3 font-medium">Type</th>
                                    <th className="px-6 py-3 font-medium">Last Action</th>
                                    <th className="px-6 py-3 font-medium">Status</th>
                                    <th className="px-6 py-3 font-medium">Custodian</th>
                                    <th className="px-6 py-3 font-medium text-right">Action</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {evidence.map((item) => (
                                    <tr key={item.evidenceId} className="group hover:bg-muted/50 transition-colors">
                                        <td className="px-6 py-4 font-medium text-foreground text-sm max-w-[120px] truncate" title={item.caseId}>
                                            {item.caseId}
                                        </td>
                                        <td className="px-6 py-4">
                                            <span className={cn(
                                                "inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-md capitalize",
                                                item.evidenceType === "PHYSICAL" && "bg-blue-500/10 text-blue-400",
                                                item.evidenceType === "DIGITAL" && "bg-purple-500/10 text-purple-400"
                                            )}>
                                                {item.evidenceType}
                                            </span>
                                        </td>
                                        <td className="px-6 py-4 max-w-xs truncate text-muted-foreground" title={item.lastReason}>
                                            {item.lastAction}
                                        </td>
                                        <td className="px-6 py-4">
                                            <span className={cn(
                                                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium capitalize border",
                                                item.status === "COLLECTED" && "border-blue-500/20 bg-blue-500/10 text-blue-400",
                                                item.status === "PROCESSING" && "border-amber-500/20 bg-amber-500/10 text-amber-400",
                                                item.status === "ANALYZED" && "border-purple-500/20 bg-purple-500/10 text-purple-400",
                                                item.status === "RELEASED" && "border-green-500/20 bg-green-500/10 text-green-400",
                                                item.status === "ARCHIVED" && "border-slate-500/20 bg-slate-500/10 text-slate-400",
                                                item.status === "DISPOSED" && "border-red-500/20 bg-red-500/10 text-red-400"
                                            )}>
                                                {item.status}
                                            </span>
                                        </td>
                                        <td className="px-6 py-4 text-muted-foreground text-sm font-mono" title={item.currentCustodian}>
                                            {item.currentCustodian.substring(0, 8)}...
                                        </td>
                                        <td className="px-6 py-4 text-right">
                                            <Link
                                                href={`/dashboard/${userId}/evidence/${item.evidenceId}`}
                                                className="inline-flex items-center justify-center p-2 rounded-md text-muted-foreground hover:text-primary hover:bg-primary/10 transition-colors"
                                            >
                                                <ArrowRight className="h-4 w-4" />
                                            </Link>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {/* Pagination - PageResponse.page is 0-indexed */}
            <div className="flex items-center justify-between text-sm text-muted-foreground pt-4">
                <span>Page {pagination.page + 1} of {pagination.totalPages}</span>
                <div className="space-x-2">
                    <button
                        disabled={pagination.page <= 0}
                        onClick={() => setPagination({ ...pagination, page: pagination.page - 1 })}
                        className="px-3 py-1.5 rounded-md border border-border hover:bg-muted disabled:opacity-50 transition-colors text-sm"
                    >
                        Previous
                    </button>
                    <button
                        disabled={pagination.page >= pagination.totalPages - 1}
                        onClick={() => setPagination({ ...pagination, page: pagination.page + 1 })}
                        className="px-3 py-1.5 rounded-md border border-border hover:bg-muted disabled:opacity-50 transition-colors text-sm"
                    >
                        Next
                    </button>
                </div>
            </div>
        </div>
    );
}
