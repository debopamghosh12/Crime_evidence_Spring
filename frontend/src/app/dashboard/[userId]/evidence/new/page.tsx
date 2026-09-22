"use client";

import { useState } from "react";
import api from "@/lib/api";
import { useRouter, useParams } from "next/navigation";
import { ArrowLeft, Save, Loader2, UploadCloud } from "lucide-react";
import Link from "next/link";
import { cn } from "@/lib/utils";

// B1: the real backend takes a multipart request with a JSON part named "metadata" (matching
// RegisterEvidenceRequest exactly) plus, for DIGITAL only, a single binary part named "file" -
// never the source repo's flat form fields or a "files" array. caseId is the case NUMBER string
// (e.g. "FE-TEST-001"), resolved server-side against an EXISTING case (E3) - registration fails
// with 404 if no such case exists yet.
export default function NewEvidencePage() {
    const router = useRouter();
    const params = useParams();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const userId = params.userId as string;

    const [file, setFile] = useState<File | null>(null);

    const [formData, setFormData] = useState({
        caseId: "",
        type: "PHYSICAL",
        description: "",
        collectedAt: new Date().toISOString().split("T")[0],
        location: "",
        notes: "",
    });

    const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
        setFormData({ ...formData, [e.target.name]: e.target.value });
    };

    const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        setFile(e.target.files && e.target.files.length > 0 ? e.target.files[0] : null);
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError("");

        try {
            const metadata = {
                caseId: formData.caseId,
                type: formData.type,
                description: formData.description,
                location: formData.location || undefined,
                collectedAt: new Date(formData.collectedAt).toISOString(),
                notes: formData.notes || undefined,
            };

            const data = new FormData();
            // A JSON part, not flat fields - RegisterEvidenceRequest is bound from @RequestPart("metadata").
            data.append("metadata", new Blob([JSON.stringify(metadata)], { type: "application/json" }));
            if (formData.type === "DIGITAL" && file) {
                data.append("file", file);
            }

            const response = await api.post("/api/evidence", data);
            router.push(`/dashboard/${userId}/evidence/${response.data.evidenceId}`);
        } catch (err: any) { // eslint-disable-line @typescript-eslint/no-explicit-any
            setError(err.response?.data?.message || "Failed to register evidence.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="max-w-2xl mx-auto space-y-6">
            <div className="flex items-center gap-4">
                <Link
                    href={`/dashboard/${userId}/evidence`}
                    className="rounded-full p-2 hover:bg-muted text-muted-foreground transition-colors"
                >
                    <ArrowLeft className="h-5 w-5" />
                </Link>
                <div>
                    <h1 className="text-2xl font-bold text-foreground">Register New Evidence</h1>
                    <p className="text-muted-foreground">Log a new item into the chain of custody.</p>
                </div>
            </div>

            <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
                <form onSubmit={handleSubmit} className="space-y-6">
                    <div className="grid gap-6 md:grid-cols-2">
                        <div className="space-y-2">
                            <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                                Case Number
                            </label>
                            <input
                                name="caseId"
                                required
                                placeholder="FE-TEST-001"
                                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                                value={formData.caseId}
                                onChange={handleChange}
                            />
                            <p className="text-xs text-muted-foreground">Must already exist (Cases page).</p>
                        </div>

                        <div className="space-y-2">
                            <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                                Evidence Type
                            </label>
                            <select
                                name="type"
                                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                                value={formData.type}
                                onChange={handleChange}
                            >
                                <option value="PHYSICAL">Physical</option>
                                <option value="DIGITAL">Digital (file upload)</option>
                            </select>
                        </div>

                        <div className="space-y-2">
                            <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                                Collection Date
                            </label>
                            <input
                                type="date"
                                name="collectedAt"
                                required
                                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                                value={formData.collectedAt}
                                onChange={handleChange}
                            />
                        </div>

                        <div className="space-y-2">
                            <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                                Location Found
                            </label>
                            <input
                                type="text"
                                name="location"
                                placeholder="123 Crime Scene Blvd, Room 4"
                                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                                value={formData.location}
                                onChange={handleChange}
                            />
                        </div>
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                            Description
                        </label>
                        <textarea
                            name="description"
                            required
                            rows={3}
                            placeholder="Detailed description of the item..."
                            className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                            value={formData.description}
                            onChange={handleChange}
                        />
                    </div>

                    {formData.type === "DIGITAL" && (
                        <div className="space-y-2">
                            <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                                File
                            </label>
                            <div className="flex items-center gap-2">
                                <input
                                    type="file"
                                    required
                                    onChange={handleFileChange}
                                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-muted-foreground file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground hover:file:cursor-pointer"
                                />
                                <UploadCloud className="h-5 w-5 text-muted-foreground" />
                            </div>
                            <p className="text-xs text-muted-foreground">
                                Encrypted automatically before storage (F2/F3) - max 50MB.
                            </p>
                        </div>
                    )}

                    <div className="space-y-2">
                        <label className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70">
                            Notes (Optional)
                        </label>
                        <textarea
                            name="notes"
                            rows={2}
                            placeholder="Any additional context..."
                            className="flex min-h-[60px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                            value={formData.notes}
                            onChange={handleChange}
                        />
                    </div>

                    {error && (
                        <div className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
                            {error}
                        </div>
                    )}

                    <div className="flex justify-end pt-4">
                        <button
                            type="submit"
                            disabled={loading}
                            className={cn(
                                "inline-flex items-center justify-center rounded-md text-sm font-medium ring-offset-background transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 bg-primary text-primary-foreground hover:bg-primary/90 h-10 px-4 py-2 w-full sm:w-auto",
                                loading && "cursor-wait"
                            )}
                        >
                            {loading ? (
                                <>
                                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                                    Registering...
                                </>
                            ) : (
                                <>
                                    <Save className="mr-2 h-4 w-4" />
                                    Register Evidence
                                </>
                            )}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
