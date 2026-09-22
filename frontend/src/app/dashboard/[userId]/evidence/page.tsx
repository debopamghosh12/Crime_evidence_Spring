"use client";

import { useEffect, useState } from "react";
import axios from "axios";
import Link from "next/link";
import { Plus, Search, FileText, Loader2, ArrowRight, Box } from "lucide-react";
import LottieLoader from "@/components/ui/LottieLoader";
import { cn } from "@/lib/utils";
import { useCrimeBox } from "@/context/CrimeBoxContext";
import { useParams } from "next/navigation";

interface Evidence {
    id: string;
    caseId: string;
    type: string;
    description: string;
    status: string;
    collectionDate: string;
    collectedBy: { fullName: string };
    currentCustodian: { fullName: string };
}

export default function EvidenceListPage() {
    const { permission, activeBox } = useCrimeBox();
    const [evidence, setEvidence] = useState<Evidence[]>([]);
    const [loading, setLoading] = useState(true);
    const [searchTerm, setSearchTerm] = useState("");
    const [pagination, setPagination] = useState({ page: 1, totalPages: 1 });
    const params = useParams();
    const userId = params.userId as string;

    useEffect(() => {
        const fetchEvidence = async () => {
            setLoading(true);
            try {
                const response = await axios.get("/api/v1/evidence", {
                    params: {
                        search: searchTerm,
                        page: pagination.page,
                        limit: 10,
                        caseId: activeBox?.caseId // Filter by active box
                    },
                });
                setEvidence(response.data.evidence);
                setPagination(response.data.pagination);
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
    }, [searchTerm, pagination.page, activeBox?.caseId]);

    return (
        <div className="space-y-8">
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between border-b border-border pb-6">
                <div>
                    <h1 className="text-2xl font-bold text-foreground">Evidence</h1>
                    <p className="text-muted-foreground text-sm mt-1 mb-2">
                        View and manage all evidence records.
                    </p>
                    {activeBox && (
                        <div className="inline-flex items-center gap-2 px-3 py-1 bg-primary/10 border border-primary/20 text-primary text-xs font-medium rounded-md">
                            <Box className="h-3 w-3" />
                            {activeBox.name} &mdash; {activeBox.caseId}
                        </div>
                    )}
                </div>
                {permission === 'read-write' && (
                    <Link
                        href={`/dashboard/${userId}/evidence/new`}
                        className="flex items-center justify-center bg-primary text-black px-6 py-2 text-sm font-bold uppercase tracking-wide hover:bg-primary/90 transition-all"
                    >
                        <Plus className="mr-2 h-4 w-4" />
                        Log New Item
                    </Link>
                )}
            </div>

            <div className="flex items-center rounded-lg border border-border bg-card px-4 py-3 focus-within:border-primary focus-within:ring-1 focus-within:ring-primary transition-all">
                <Search className="mr-3 h-4 w-4 text-muted-foreground" />
                <input
                    type="text"
                    placeholder="Search by case ID, description, or keyword..."
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
                                    <th className="px-6 py-3 font-medium">Case ID</th>
                                    <th className="px-6 py-3 font-medium">Type</th>
                                    <th className="px-6 py-3 font-medium">Description</th>
                                    <th className="px-6 py-3 font-medium">Status</th>
                                    <th className="px-6 py-3 font-medium">Custodian</th>
                                    <th className="px-6 py-3 font-medium text-right">Action</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {evidence.map((item) => (
                                    <tr key={item.id} className="group hover:bg-muted/50 transition-colors">
                                        <td className="px-6 py-4 font-medium text-foreground text-sm max-w-[120px] truncate" title={item.caseId}>
                                            {item.caseId.substring(0, 8)}...
                                        </td>
                                        <td className="px-6 py-4">
                                            <span className={cn(
                                                "inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-md capitalize",
                                                item.type.toLowerCase() === "physical" && "bg-blue-500/10 text-blue-400",
                                                item.type.toLowerCase() === "digital" && "bg-purple-500/10 text-purple-400",
                                                item.type.toLowerCase() === "testimonial" && "bg-amber-500/10 text-amber-400"
                                            )}>
                                                {item.type}
                                            </span>
                                        </td>
                                        <td className="px-6 py-4 max-w-xs truncate text-muted-foreground" title={item.description}>
                                            {item.description}
                                        </td>
                                        <td className="px-6 py-4">
                                            <span className={cn(
                                                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium capitalize border",
                                                item.status.toLowerCase() === "collected" && "border-blue-500/20 bg-blue-500/10 text-blue-400",
                                                item.status.toLowerCase() === "in-custody" && "border-amber-500/20 bg-amber-500/10 text-amber-400",
                                                item.status.toLowerCase() === "analyzed" && "border-purple-500/20 bg-purple-500/10 text-purple-400",
                                                item.status.toLowerCase() === "secured" && "border-green-500/20 bg-green-500/10 text-green-400",
                                                item.status.toLowerCase() === "archived" && "border-slate-500/20 bg-slate-500/10 text-slate-400"
                                            )}>
                                                {item.status}
                                            </span>
                                        </td>
                                        <td className="px-6 py-4 text-muted-foreground text-sm">
                                            {item.currentCustodian?.fullName || "—"}
                                        </td>
                                        <td className="px-6 py-4 text-right">
                                            <Link
                                                href={`/dashboard/${userId}/evidence/${item.id}`}
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

            {/* Pagination */}
            <div className="flex items-center justify-between text-sm text-muted-foreground pt-4">
                <span>Page {pagination.page} of {pagination.totalPages}</span>
                <div className="space-x-2">
                    <button
                        disabled={pagination.page <= 1}
                        onClick={() => setPagination({ ...pagination, page: pagination.page - 1 })}
                        className="px-3 py-1.5 rounded-md border border-border hover:bg-muted disabled:opacity-50 transition-colors text-sm"
                    >
                        Previous
                    </button>
                    <button
                        disabled={pagination.page >= pagination.totalPages}
                        onClick={() => setPagination({ ...pagination, page: pagination.page + 1 })}
                        className="px-3 py-1.5 rounded-md border border-border hover:bg-muted disabled:opacity-50 transition-colors text-sm"
                    >
                        Next
                    </button>
                </div>
            </div>
        </div >
    );
}
