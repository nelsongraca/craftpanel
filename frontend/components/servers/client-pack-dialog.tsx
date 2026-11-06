"use client";

import {useCallback, useEffect, useState} from "react";
import {AlertTriangle, Download, Loader2, Package} from "lucide-react";
import {downloadClientPack, previewClientPack} from "@/lib/generated/sdk.gen";
import type {ClientPackPreview} from "@/lib/types";
import {Button} from "@/components/ui/button";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {fmtBytes} from "@/lib/utils/format";

interface ClientPackDialogProps {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    serverId: string;
}

type Phase = "loading" | "ready" | "downloading" | "error";

export function ClientPackDialog({open, onOpenChange, serverId}: ClientPackDialogProps) {
    const [phase, setPhase] = useState<Phase>("loading");
    const [preview, setPreview] = useState<ClientPackPreview | null>(null);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setPhase("loading");
        setError(null);
        setPreview(null);
        const res = await previewClientPack({path: {id: serverId}});
        if (res.error || !res.data) {
            setError(
                (res.error as {message?: string} | undefined)?.message ??
                    "Failed to build the client pack preview",
            );
            setPhase("error");
            return;
        }
        setPreview(res.data);
        setPhase("ready");
    }, [serverId]);

    useEffect(() => {
        if (open) void load();
    }, [open, load]);

    async function handleDownload() {
        if (!preview) return;
        setPhase("downloading");
        setError(null);
        const res = await downloadClientPack({path: {id: serverId}});
        if (res.error || !res.data) {
            setError(
                (res.error as {message?: string} | undefined)?.message ?? "Failed to download the client pack",
            );
            setPhase("error");
            return;
        }
        const blob = res.data as Blob;
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = preview.pack_file_name;
        a.click();
        URL.revokeObjectURL(url);
        setPhase("ready");
    }

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="sm:max-w-lg">
                <DialogHeader>
                    <DialogTitle className="flex items-center gap-2">
                        <Package className="h-4 w-4 text-accent" />
                        Client Pack
                    </DialogTitle>
                    <DialogDescription>
                        Export this server&apos;s client-required mods as a Modrinth pack you can import
                        into Prism Launcher.
                    </DialogDescription>
                </DialogHeader>

                {phase === "loading" && (
                    <div className="flex items-center gap-2 py-6 text-sm text-text-dim">
                        <Loader2 className="h-4 w-4 animate-spin" />
                        Resolving mod versions…
                    </div>
                )}

                {(phase === "ready" || phase === "downloading") && preview && (
                    <div className="space-y-4">
                        <div className="rounded-lg border border-border bg-bg/40 p-3 text-xs">
                            <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                                <span className="text-text-muted">File</span>
                                <span className="truncate font-mono text-text-primary">
                                    {preview.pack_file_name}
                                </span>
                                <span className="text-text-muted">Minecraft</span>
                                <span className="font-mono text-text-primary">{preview.mc_version}</span>
                                <span className="text-text-muted">Loader</span>
                                <span className="font-mono text-text-primary">
                                    {preview.loader} {preview.loader_version}
                                </span>
                            </div>
                        </div>

                        <div>
                            <p className="mb-1.5 text-xs text-text-dim">
                                Included mods ({preview.mods.length})
                            </p>
                            <ul className="max-h-48 space-y-1 overflow-y-auto rounded-lg border border-border bg-bg/40 p-2">
                                {preview.mods.map((mod) => (
                                    <li
                                        key={mod.project_id}
                                        className="flex items-center justify-between gap-3 text-xs"
                                    >
                                        <span className="min-w-0 flex-1 truncate text-text-primary">
                                            {mod.display_name}
                                        </span>
                                        <span className="shrink-0 font-mono text-text-muted">
                                            {mod.version_number}
                                        </span>
                                        <span className="w-16 shrink-0 text-right font-mono text-text-muted">
                                            {fmtBytes(mod.file_size)}
                                        </span>
                                    </li>
                                ))}
                            </ul>
                        </div>

                        {preview.skipped.length > 0 && (
                            <div>
                                <p className="mb-1.5 text-xs text-text-dim">
                                    Not needed on the client ({preview.skipped.length})
                                </p>
                                <ul className="max-h-32 space-y-1 overflow-y-auto rounded-lg border border-border bg-bg/40 p-2">
                                    {preview.skipped.map((mod) => (
                                        <li
                                            key={mod.project_id}
                                            className="flex items-center justify-between gap-3 text-xs"
                                        >
                                            <span className="min-w-0 flex-1 truncate text-text-primary">
                                                {mod.display_name}
                                            </span>
                                            <span className="shrink-0 text-text-muted">{mod.reason}</span>
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </div>
                )}

                {phase === "error" && (
                    <div className="flex items-start gap-2 rounded-lg border border-error/30 bg-error/10 p-3 text-sm text-error">
                        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                        <span>{error ?? "Failed to build the client pack"}</span>
                    </div>
                )}

                <DialogFooter>
                    <Button variant="outline" size="sm" onClick={() => onOpenChange(false)}>
                        Close
                    </Button>
                    {phase === "error" ? (
                        <Button size="sm" onClick={() => void load()}>
                            Retry
                        </Button>
                    ) : (
                        <Button
                            size="sm"
                            onClick={() => void handleDownload()}
                            disabled={phase !== "ready" || !preview}
                        >
                            {phase === "downloading" ? (
                                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                            ) : (
                                <Download className="h-3.5 w-3.5" />
                            )}
                            {phase === "downloading" ? "Downloading…" : "Download .mrpack"}
                        </Button>
                    )}
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
