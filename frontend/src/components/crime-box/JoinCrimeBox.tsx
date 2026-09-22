"use client";

import { useState } from "react";
import { useCrimeBox } from "@/context/CrimeBoxContext";
import { LogIn, ArrowRight, AlertCircle, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface JoinCrimeBoxProps {
  onJoinSuccess?: () => void;
}

export default function JoinCrimeBox({ onJoinSuccess }: JoinCrimeBoxProps) {
  const { joinBox } = useCrimeBox();
  const [key, setKey] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleJoin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    const success = await joinBox(key.trim());
    if (!success) {
      setError("Invalid key or insufficient permissions.");
    } else {
      setKey("");
      onJoinSuccess?.();
    }
    setLoading(false);
  };

  return (
    <div className="rounded-lg border border-border bg-card p-6">
      <div className="flex items-center gap-3 mb-4 border-b border-border pb-4">
        <div className="p-2 rounded-md bg-primary/10 text-primary">
          <LogIn className="h-5 w-5" />
        </div>
        <h2 className="text-lg font-semibold text-foreground">Join a Crime Box</h2>
      </div>

      <p className="text-sm text-muted-foreground mb-4">
        Enter a private or public key to access the evidence in a case.
      </p>

      <form onSubmit={handleJoin} className="space-y-4">
        <div>
          <input
            type="text"
            required
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder="Enter private or public key"
            className="w-full rounded-md border border-border bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:ring-1 focus:ring-primary focus:outline-none"
          />
        </div>

        {error && (
          <div className="rounded-md p-3 bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
            <AlertCircle className="h-4 w-4 shrink-0" /> {error}
          </div>
        )}

        <button
          type="submit"
          disabled={loading || !key}
          className={cn(
            "w-full rounded-md flex items-center justify-center gap-2 bg-primary text-primary-foreground py-2.5 text-sm font-semibold hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed",
            loading && "cursor-wait"
          )}
        >
          {loading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <>
              Join Box <ArrowRight className="h-4 w-4" />
            </>
          )}
        </button>
      </form>
    </div>
  );
}
