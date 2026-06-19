"use client";

import {X} from "lucide-react";

const INPUT = "w-full bg-surface-high border border-border rounded px-3 py-2 text-[13px] text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent/60";
const BTN_PRIMARY = "px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider bg-accent text-bg hover:bg-accent-bright transition-colors";
const BTN_GHOST = "px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary hover:bg-surface-high transition-colors border border-border";

function Modal({title, onClose, children}: { title: string; onClose: () => void; children: React.ReactNode }) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface border border-border rounded-md w-full max-w-md shadow-2xl max-h-[90vh] overflow-y-auto">
                <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10">
                    <h2 className="text-[13px] font-heading font-bold uppercase tracking-widest text-text-primary">{title}</h2>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary transition-colors">
                        <X size={16}/>
                    </button>
                </div>
                <div className="p-5">{children}</div>
            </div>
        </div>
    );
}

function Field({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex flex-col gap-1.5">
            <label className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">{label}</label>
            {children}
        </div>
    );
}

export {INPUT, BTN_PRIMARY, BTN_GHOST, Modal, Field};
