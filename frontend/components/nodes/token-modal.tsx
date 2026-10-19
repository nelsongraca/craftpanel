"use client";

import {useState} from "react";
import {Dialog, DialogContent, DialogHeader, DialogTitle} from "@/components/ui/dialog";

export function TokenModal({nodeKey, onClose}: {nodeKey: string; onClose: () => void}) {
    const [copied, setCopied] = useState(false);
    const [open, setOpen] = useState(true);

    function copy() {
        navigator.clipboard.writeText(nodeKey).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    }

    function handleClose() {
        setOpen(false);
        onClose();
    }

    return (
        <Dialog open={open} onOpenChange={setOpen}>
            <DialogContent className="w-[480px]">
                <DialogHeader>
                    <DialogTitle>New Node Key</DialogTitle>
                </DialogHeader>

                <div className="rounded border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning">
                    The old key has been invalidated. The agent will be rejected on its next connection and must
                    re-register using the bootstrap token.
                </div>

                <div className="space-y-2">
                    <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">Node Key</p>
                    <div className="flex items-center gap-2">
                        <code className="flex-1 rounded border border-border bg-surface px-3 py-2 font-mono text-xs break-all text-text-primary">
                            {nodeKey}
                        </code>
                        <button
                            onClick={copy}
                            className="shrink-0 rounded border border-border px-3 py-2 font-heading text-xs font-bold tracking-widest text-text-muted uppercase transition-colors hover:bg-surface-high"
                        >
                            {copied ? "Copied!" : "Copy"}
                        </button>
                    </div>
                </div>

                <div className="mt-4 flex justify-end">
                    <button
                        onClick={handleClose}
                        className="rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-on-accent uppercase transition-colors hover:bg-accent-bright"
                    >
                        Done
                    </button>
                </div>
            </DialogContent>
        </Dialog>
    );
}
