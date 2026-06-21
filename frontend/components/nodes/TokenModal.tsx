"use client";

import {useState} from "react";
import {X} from "lucide-react";

export function TokenModal({nodeKey, onClose}: { nodeKey: string; onClose: () => void }) {
    const [copied, setCopied] = useState(false);

    function copy() {
        navigator.clipboard.writeText(nodeKey).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface-higher border border-border rounded shadow-2xl w-[480px] p-6">
                <div className="flex items-center justify-between mb-4">
                    <p className="text-[13px] font-heading font-bold uppercase tracking-widest text-text-primary">
                        New Node Key
                    </p>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary">
                        <X size={14}/>
                    </button>
                </div>

                <div className="mb-4 text-[12px] text-warning bg-warning/10 border border-warning/30 rounded px-3 py-2">
                    The old key has been invalidated. The agent will be rejected on its next connection and
                    must re-register using the bootstrap token.
                </div>

                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1">
                    Node Key
                </p>
                <div className="flex items-center gap-2">
                    <code className="flex-1 font-mono text-[12px] text-text-primary bg-surface border border-border rounded px-3 py-2 break-all">
                        {nodeKey}
                    </code>
                    <button
                        onClick={copy}
                        className="shrink-0 px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-widest border border-border rounded text-text-muted hover:bg-surface-high transition-colors"
                    >
                        {copied ? "Copied!" : "Copy"}
                    </button>
                </div>

                <div className="flex justify-end mt-5">
                    <button
                        onClick={onClose}
                        className="px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors"
                    >
                        Done
                    </button>
                </div>
            </div>
        </div>
    );
}
