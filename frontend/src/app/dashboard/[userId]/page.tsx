"use client";

import { useEffect, useState } from "react";
import api from "@/lib/api";
import { useAuth } from "@/context/AuthContext";
import Link from "next/link";

// Matches com.blockevidence.backend.dto.DashboardResponse exactly (GET /api/dashboard, H2).
interface DashboardStats {
    totalEvidence: number;
    byStatus: Record<string, number>;
    byType: Record<string, number>;
    byCase: Record<string, number>;
    activityByDay: { date: string; count: number }[];
}

// Matches com.blockevidence.backend.dto.ActivityEntryResponse (GET /api/activity, H3) - actorId is a
// plain user id (no user-lookup endpoint, A4 was never built), not a resolved name.
interface ActivityEntry {
    txId: string;
    evidenceId: string;
    caseId: string;
    action: string;
    actorId: string;
    actorRole: string;
    reason: string;
    ledgerAt: string;
}

export default function DashboardPage() {
    const { user } = useAuth();
    const [stats, setStats] = useState<DashboardStats | null>(null);
    const [recentActivity, setRecentActivity] = useState<ActivityEntry[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchDashboard = async () => {
            try {
                const [statsRes, activityRes] = await Promise.all([
                    api.get("/api/dashboard"),
                    api.get("/api/activity", { params: { size: 5 } }),
                ]);
                setStats(statsRes.data);
                setRecentActivity(activityRes.data.items || []);
            } catch (error) {
                console.error("Failed to fetch dashboard data", error);
            } finally {
                setLoading(false);
            }
        };

        fetchDashboard();
    }, []);

    return (
        <div className="space-y-8">
            <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                <h1 className="text-2xl font-bold text-foreground">
                    Welcome back, {user?.fullName}
                </h1>
                <div className="mt-2 inline-flex items-center rounded-full border border-border px-2.5 py-0.5 text-xs font-semibold text-foreground">
                    Role: {user?.role}
                </div>
            </div>

            <div className="grid gap-8 md:grid-cols-2">
                {/* Left column: status/type breakdown */}
                <div className="space-y-6">
                    <div className="rounded-lg border border-border bg-card p-6">
                        <h3 className="font-medium text-foreground mb-4">Evidence by Status</h3>
                        {loading ? (
                            <p className="text-sm text-muted-foreground">Loading...</p>
                        ) : Object.keys(stats?.byStatus || {}).length === 0 ? (
                            <p className="text-sm text-muted-foreground italic">No evidence registered yet.</p>
                        ) : (
                            <div className="space-y-2">
                                {Object.entries(stats!.byStatus).map(([status, count]) => (
                                    <div key={status} className="flex items-center justify-between text-sm">
                                        <span className="text-muted-foreground">{status}</span>
                                        <span className="font-semibold text-foreground">{count}</span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>

                    <div className="rounded-lg border border-border bg-card p-6">
                        <h3 className="font-medium text-foreground mb-4">Evidence by Type</h3>
                        {loading ? (
                            <p className="text-sm text-muted-foreground">Loading...</p>
                        ) : Object.keys(stats?.byType || {}).length === 0 ? (
                            <p className="text-sm text-muted-foreground italic">No evidence registered yet.</p>
                        ) : (
                            <div className="space-y-2">
                                {Object.entries(stats!.byType).map(([type, count]) => (
                                    <div key={type} className="flex items-center justify-between text-sm">
                                        <span className="text-muted-foreground">{type}</span>
                                        <span className="font-semibold text-foreground">{count}</span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>

                {/* Right column: totals + recent activity */}
                <div className="space-y-6">
                    <div className="grid gap-6 sm:grid-cols-1">
                        <div className="p-6 rounded-xl bg-card border border-border shadow-sm">
                            <h3 className="font-medium text-foreground">Total Evidence</h3>
                            <p className="text-3xl font-bold text-primary mt-2">
                                {loading ? "..." : stats?.totalEvidence ?? 0}
                            </p>
                            <p className="text-xs text-muted-foreground mt-1">System-wide</p>
                        </div>
                    </div>

                    <div className="rounded-lg border border-border bg-card p-6">
                        <h3 className="font-medium text-foreground mb-4">Recent Activity</h3>
                        <div className="space-y-4">
                            {recentActivity.length === 0 && !loading ? (
                                <p className="text-sm text-muted-foreground italic">No recent activity.</p>
                            ) : (
                                recentActivity.map((log) => (
                                    <div key={log.txId} className="flex items-start gap-3 text-sm">
                                        <div className="mt-0.5 h-2 w-2 rounded-full bg-muted-foreground/50 shrink-0" />
                                        <p className="text-muted-foreground">
                                            <span className="font-mono text-xs text-foreground">{log.actorId.substring(0, 8)}...</span>{" "}
                                            ({log.actorRole}) {log.action.replace(/_/g, " ")} on{" "}
                                            <Link href={`/dashboard/${user?.id}/evidence/${log.evidenceId}`} className="font-medium text-primary hover:underline">
                                                {log.caseId}
                                            </Link>.
                                        </p>
                                    </div>
                                ))
                            )}
                        </div>
                        <Link href={`/dashboard/${user?.id}/activity`} className="mt-4 block text-center text-xs text-primary hover:underline">
                            View full activity feed →
                        </Link>
                    </div>
                </div>
            </div>
        </div>
    );
}
