"use client";

import { useEffect, useState, useCallback } from "react";
import api from "@/lib/api";
import { Bell, CheckCheck, Loader2, Info, AlertTriangle } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import LottieLoader from "@/components/ui/LottieLoader";

// Matches com.blockevidence.backend.dto.NotificationResponse exactly (GET /api/notifications, H4) -
// "read" is derived from readAt being non-null, not a separate boolean field. type is one of the real
// notification types (transfer received, status change, disposal event, tamper alert).
interface Notification {
  id: string;
  type: string;
  evidenceId: string | null;
  caseId: string | null;
  message: string;
  createdAt: string;
  readAt: string | null;
}

function timeAgo(date: string) {
  const secs = Math.floor((Date.now() - new Date(date).getTime()) / 1000);
  if (secs < 60) return "just now";
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

export default function NotificationsPage() {
  const params = useParams();
  const userId = params.userId as string;

  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(true);
  const [marking, setMarking] = useState(false);

  const fetchNotifications = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.get("/api/notifications", { params: { size: 50 } });
      setNotifications(r.data.items || []);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetchNotifications(); }, [fetchNotifications]);

  const markRead = async (id: string) => {
    try {
      await api.post(`/api/notifications/${id}/read`);
      setNotifications(prev => prev.map(n => n.id === id ? { ...n, readAt: new Date().toISOString() } : n));
    } catch (e) { console.error(e); }
  };

  const markAllRead = async () => {
    // No bulk endpoint exists on the backend - each unread notification is marked individually
    // (real calls, not simulated), not a single fake "read-all" request.
    setMarking(true);
    const unreadIds = notifications.filter(n => !n.readAt).map(n => n.id);
    try {
      await Promise.all(unreadIds.map(id => api.post(`/api/notifications/${id}/read`)));
      setNotifications(prev => prev.map(n => unreadIds.includes(n.id) ? { ...n, readAt: new Date().toISOString() } : n));
    } catch (e) { console.error(e); }
    finally { setMarking(false); }
  };

  const unread = notifications.filter(n => !n.readAt).length;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground flex items-center gap-2">
            <Bell className="h-6 w-6 text-primary" /> Notifications
            {unread > 0 && <span className="ml-1 px-2 py-0.5 rounded-full bg-primary text-primary-foreground text-xs font-bold">{unread}</span>}
          </h1>
          <p className="text-sm text-muted-foreground mt-1">{unread} unread</p>
        </div>
        {unread > 0 && (
          <button
            onClick={markAllRead}
            disabled={marking}
            className="flex items-center gap-2 px-4 py-2 rounded-lg border border-border text-sm text-muted-foreground hover:text-foreground hover:border-primary/30 transition-all disabled:opacity-50"
          >
            {marking ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCheck className="h-4 w-4" />}
            Mark all read
          </button>
        )}
      </div>

      {loading ? (
        <div className="flex items-center justify-center h-40"><LottieLoader size={120} /></div>
      ) : notifications.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-64 text-muted-foreground space-y-3">
          <Bell className="h-12 w-12 opacity-30" />
          <p className="text-sm">No notifications yet.</p>
        </div>
      ) : (
        <div className="space-y-2">
          {notifications.map(n => {
            const isRead = !!n.readAt;
            const icon = n.type === "TAMPER_ALERT"
              ? <AlertTriangle className="h-4 w-4 text-destructive" />
              : <Info className="h-4 w-4 text-blue-400" />;
            const content = (
              <div
                onClick={() => !isRead && markRead(n.id)}
                className={`flex items-start gap-4 p-4 rounded-xl border transition-all cursor-pointer hover:border-primary/30 ${isRead ? "border-border bg-card opacity-60" : "border-primary/20 bg-primary/5"
                  }`}
              >
                <div className="mt-0.5 shrink-0">{icon}</div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-sm font-semibold text-foreground">{n.type.replace(/_/g, " ")}</p>
                    <span className="text-xs text-muted-foreground shrink-0">{timeAgo(n.createdAt)}</span>
                  </div>
                  <p className="text-sm text-muted-foreground mt-0.5">{n.message}</p>
                </div>
                {!isRead && <div className="w-2 h-2 rounded-full bg-primary shrink-0 mt-1.5" />}
              </div>
            );

            return n.evidenceId ? (
              <Link href={`/dashboard/${userId}/evidence/${n.evidenceId}`} key={n.id} onClick={() => !isRead && markRead(n.id)}>
                {content}
              </Link>
            ) : <div key={n.id}>{content}</div>;
          })}
        </div>
      )}
    </div>
  );
}
