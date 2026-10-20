"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {AlertTriangle, CheckCircle2, Loader2, XCircle} from "lucide-react";
import {uploadServerFiles} from "@/lib/generated/sdk.gen";
import type {BatchUploadResponse} from "@/lib/generated/types.gen";
import {Button} from "@/components/ui/button";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {toUploadFile, type UploadItem} from "@/lib/upload-drop";
import {fmtBytes} from "@/lib/utils/format";

interface UploadDialogProps {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    serverId: string;
    destination: string;
    items: UploadItem[];
    conflicts: string[];
    /** False only for a single new file: skips the summary and uploads immediately. */
    requiresConfirmation: boolean;
    onFinished: () => void;
}

type Phase = "confirm" | "uploading" | "done" | "error";

const MAX_LISTED_CONFLICTS = 8;

export function UploadDialog({
    open,
    onOpenChange,
    serverId,
    destination,
    items,
    conflicts,
    requiresConfirmation,
    onFinished,
}: UploadDialogProps) {
    const [phase, setPhase] = useState<Phase>(requiresConfirmation ? "confirm" : "uploading");
    const [result, setResult] = useState<BatchUploadResponse | null>(null);
    const [transportError, setTransportError] = useState<string | null>(null);
    const startedRef = useRef(false);

    const start = useCallback(async () => {
        if (startedRef.current) return;
        startedRef.current = true;
        setPhase("uploading");
        setTransportError(null);
        try {
            const {data, error} = await uploadServerFiles({
                path: {id: serverId},
                query: {path: destination},
                body: {files: items.map(toUploadFile)},
            });
            if (error || !data) {
                setTransportError((error as {message?: string} | undefined)?.message ?? "Upload failed");
                setPhase("error");
                // Files written before a transfer-level failure are still on disk.
                onFinished();
                return;
            }
            setResult(data);
            setPhase("done");
            onFinished();
        } catch {
            setTransportError("Upload failed - the connection was interrupted");
            setPhase("error");
            onFinished();
        }
    }, [destination, items, onFinished, serverId]);

    // A single non-conflicting file uploads straight away; everything else waits for a click.
    useEffect(() => {
        if (open && !requiresConfirmation) void start();
    }, [open, requiresConfirmation, start]);

    function handleOpenChange(next: boolean) {
        if (!next && phase === "uploading") return;
        onOpenChange(next);
    }

    const totalBytes = items.reduce((sum, item) => sum + item.file.size, 0);
    const confirmedUploads = result?.uploaded ?? 0;
    const failed = result?.failed ?? 0;

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent data-testid="upload-dialog" className="sm:max-w-lg">
                <DialogHeader>
                    <DialogTitle>
                        {phase === "done"
                            ? failed > 0
                                ? "Upload finished with failures"
                                : "Upload complete"
                            : phase === "error"
                              ? "Upload failed"
                              : "Upload files"}
                    </DialogTitle>
                    <DialogDescription className="truncate font-mono text-xs">
                        {items.length} {items.length === 1 ? "file" : "files"} · {fmtBytes(totalBytes)} · to{" "}
                        {destination}
                    </DialogDescription>
                </DialogHeader>

                {phase === "confirm" && (
                    <div className="flex flex-col gap-3 text-sm">
                        <p className="text-text-dim">
                            {items.some((item) => item.relativePath.includes("/"))
                                ? "Folder structure is preserved. Empty folders are not uploaded."
                                : "The selected files will be uploaded into this directory."}
                        </p>
                        {conflicts.length > 0 && (
                            <div
                                data-testid="upload-conflicts"
                                className="rounded border border-warning/30 bg-warning/10 p-3"
                            >
                                <p className="flex items-center gap-1.5 font-bold text-warning">
                                    <AlertTriangle size={14} />
                                    {conflicts.length}{" "}
                                    {conflicts.length === 1 ? "file already exists" : "files already exist"}
                                </p>
                                <p className="mt-1 text-xs text-text-dim">Uploading will overwrite them.</p>
                                <ul className="mt-2 max-h-32 overflow-y-auto font-mono text-xs text-text-dim">
                                    {conflicts.slice(0, MAX_LISTED_CONFLICTS).map((path) => (
                                        <li key={path} className="truncate">
                                            {path}
                                        </li>
                                    ))}
                                    {conflicts.length > MAX_LISTED_CONFLICTS && (
                                        <li>+{conflicts.length - MAX_LISTED_CONFLICTS} more</li>
                                    )}
                                </ul>
                            </div>
                        )}
                    </div>
                )}

                {phase === "uploading" && (
                    <div className="flex items-center gap-2 py-4 text-sm text-text-dim">
                        <Loader2 size={16} className="animate-spin text-accent" />
                        Uploading {items.length} {items.length === 1 ? "file" : "files"}…
                    </div>
                )}

                {phase === "error" && (
                    <p
                        data-testid="upload-error"
                        className="flex items-start gap-2 rounded border border-error/30 bg-error/10 p-3 text-sm text-error"
                    >
                        <XCircle size={16} className="mt-0.5 shrink-0" />
                        {transportError}
                    </p>
                )}

                {phase === "done" && result && (
                    <div className="flex flex-col gap-2 text-sm">
                        <p className="flex items-center gap-1.5 font-bold text-text-primary">
                            {failed === 0 ? (
                                <CheckCircle2 size={16} className="text-healthy" />
                            ) : (
                                <AlertTriangle size={16} className="text-warning" />
                            )}
                            {confirmedUploads} uploaded · {failed} failed
                        </p>
                        {failed > 0 && (
                            <ul
                                data-testid="upload-failures"
                                className="max-h-48 overflow-y-auto rounded border border-border bg-bg p-2 font-mono text-xs"
                            >
                                {result.files
                                    .filter((file) => !file.success)
                                    .map((file) => (
                                        <li key={file.path} className="flex flex-col py-0.5">
                                            <span className="truncate text-text-primary">{file.path}</span>
                                            <span className="text-error">{file.error ?? "Upload failed"}</span>
                                        </li>
                                    ))}
                            </ul>
                        )}
                    </div>
                )}

                <DialogFooter>
                    {phase === "confirm" && (
                        <>
                            <Button variant="outline" onClick={() => handleOpenChange(false)}>
                                Cancel
                            </Button>
                            <Button onClick={() => void start()}>
                                {conflicts.length > 0 ? "Overwrite and upload" : "Upload"}
                            </Button>
                        </>
                    )}
                    {phase === "done" && <Button onClick={() => handleOpenChange(false)}>Done</Button>}
                    {phase === "error" && <Button onClick={() => handleOpenChange(false)}>Close</Button>}
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
