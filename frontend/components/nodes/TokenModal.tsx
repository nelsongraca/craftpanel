"use client";

import {useState} from "react";
import {Dialog, DialogContent, DialogHeader, DialogTitle} from "@/components/ui/dialog";

export function TokenModal({nodeKey, onClose}: { nodeKey: string; onClose: () => void }) {
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

                <div className="text-xs text-warning bg-warning/10 border border-warning/30 rounded px-3 py-2">
                    The old key has been invalidated. The agent will be rejected on its next connection and
                    must re-register using the bootstrap token.
                </div>

                <div className="space-y-2">
                    <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                        Node Key
                    </p>
                    <div className="flex items-center gap-2">
                        <code className="flex-1 font-mono text-xs text-text-primary bg-surface border border-border rounded px-3 py-2 break-all">
                            {nodeKey}
                        </code>
                        <button
                            onClick={copy}
                            className="shrink-0 px-3 py-2 text-xs font-heading font-bold uppercase tracking-widest border border-border rounded text-text-muted hover:bg-surface-high transition-colors"
                        >
                            {copied ? "Copied!" : "Copy"}
                        </button>
                    </div>
                </div>

                <div className="flex justify-end mt-4">
                    <button
                        onClick={handleClose}
                        className="px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors"
                    >
                        Done
                    </button>
                </div>
            </DialogContent>
        </Dialog>
    );
}
