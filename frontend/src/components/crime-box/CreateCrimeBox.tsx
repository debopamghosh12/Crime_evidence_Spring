"use client";

import { useState } from "react";
import { useCrimeBox } from "@/context/CrimeBoxContext";
import { Plus, Key, Copy, Check } from "lucide-react";

interface CreateCrimeBoxProps {
  onCreateSuccess?: () => void;
}

export default function CreateCrimeBox({ onCreateSuccess }: CreateCrimeBoxProps) {
  const { createBox } = useCrimeBox();
  const [name, setName] = useState("");
  const [caseId, setCaseId] = useState("");
  const [keys, setKeys] = useState<{ privateKey: string; publicKey: string } | null>(null);
  const [copiedPrivate, setCopiedPrivate] = useState(false);
  const [copiedPublic, setCopiedPublic] = useState(false);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    const newKeys = await createBox(name, caseId);
    if (newKeys) {
      setKeys(newKeys);
      setName("");
      setCaseId("");
    }
  };

  const copyToClipboard = (text: string, isPrivate: boolean) => {
    navigator.clipboard.writeText(text);
    if (isPrivate) {
      setCopiedPrivate(true);
      setTimeout(() => setCopiedPrivate(false), 2000);
    } else {
      setCopiedPublic(true);
      setTimeout(() => setCopiedPublic(false), 2000);
    }
  };

  return (
    <div className="rounded-lg border border-border bg-card p-6">
      <div className="flex items-center gap-3 mb-6 border-b border-border pb-4">
        <div className="p-2 rounded-md bg-primary/10 text-primary">
          <Plus className="h-5 w-5" />
        </div>
        <h2 className="text-lg font-semibold text-foreground">Create Crime Box</h2>
      </div>

      {!keys ? (
        <form onSubmit={handleCreate} className="space-y-4">
          <div>
            <label className="text-sm font-medium text-foreground">Box Name</label>
            <input
              type="text"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Operation Bluebird"
              className="w-full mt-1.5 rounded-md border border-border bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:ring-1 focus:ring-primary focus:outline-none"
            />
          </div>
          <div>
            <label className="text-sm font-medium text-foreground">Case ID</label>
            <input
              type="text"
              required
              value={caseId}
              onChange={(e) => setCaseId(e.target.value)}
              placeholder="e.g. CASE-2024-001"
              className="w-full mt-1.5 rounded-md border border-border bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:ring-1 focus:ring-primary focus:outline-none"
            />
          </div>
          <button
            type="submit"
            className="w-full rounded-md bg-primary text-primary-foreground py-2.5 text-sm font-semibold hover:bg-primary/90 transition-colors"
          >
            Create Box
          </button>
        </form>
      ) : (
        <div className="space-y-4">
          <div className="rounded-md bg-primary/10 p-3 border border-primary/20 text-primary">
            <p className="font-medium text-sm flex items-center gap-2">
              <Check className="h-4 w-4" /> Box created successfully. Share these keys with your team.
            </p>
          </div>

          <div className="space-y-3">
            <div className="rounded-md bg-muted/50 p-3 border border-border space-y-1.5">
              <label className="text-xs font-medium text-foreground flex items-center gap-1.5">
                <Key className="h-3 w-3" /> Private Key (Read &amp; Write — Officers)
              </label>
              <div className="flex items-center gap-2">
                <code className="flex-1 rounded-md bg-background p-2.5 text-xs font-mono border border-border text-foreground truncate">
                  {keys.privateKey}
                </code>
                <button
                  onClick={() => copyToClipboard(keys.privateKey, true)}
                  className="p-2 rounded-md hover:bg-muted border border-border transition-colors text-muted-foreground hover:text-foreground"
                  title="Copy Private Key"
                >
                  {copiedPrivate ? <Check className="h-4 w-4 text-primary" /> : <Copy className="h-4 w-4" />}
                </button>
              </div>
            </div>

            <div className="rounded-md bg-muted/50 p-3 border border-border space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground flex items-center gap-1.5">
                <Key className="h-3 w-3" /> Public Key (Read Only — Lawyers &amp; Judges)
              </label>
              <div className="flex items-center gap-2">
                <code className="flex-1 rounded-md bg-background p-2.5 text-xs font-mono border border-border text-muted-foreground truncate">
                  {keys.publicKey}
                </code>
                <button
                  onClick={() => copyToClipboard(keys.publicKey, false)}
                  className="p-2 rounded-md hover:bg-muted border border-border transition-colors text-muted-foreground hover:text-foreground"
                  title="Copy Public Key"
                >
                  {copiedPublic ? <Check className="h-4 w-4 text-primary" /> : <Copy className="h-4 w-4" />}
                </button>
              </div>
            </div>
          </div>

          <div className="flex gap-3 pt-2">
            <button
              onClick={() => setKeys(null)}
              className="flex-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              Create Another
            </button>
            {onCreateSuccess && (
              <button
                onClick={onCreateSuccess}
                className="flex-1 rounded-md bg-primary text-primary-foreground py-2 text-sm font-semibold hover:bg-primary/90 transition-colors"
              >
                Go to Evidence →
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
