"use client";

/** The Discard/Save strip shown under a config section while there are unsaved changes. */
export function UnsavedBar({onDiscard, onSave, saving}: {onDiscard: () => void; onSave: () => void; saving: boolean}) {
    return (
        <div className="flex items-center justify-between border-t border-border pt-2">
            <span className="text-xs text-text-muted">Unsaved changes</span>
            <div className="flex gap-2">
                <button
                    onClick={onDiscard}
                    className="rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-text-dim uppercase transition-colors hover:border-text-muted"
                >
                    Discard
                </button>
                <button
                    onClick={onSave}
                    disabled={saving}
                    className="rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-on-accent uppercase transition-colors hover:bg-accent-bright disabled:opacity-60"
                >
                    {saving ? "Saving\u2026" : "Save"}
                </button>
            </div>
        </div>
    );
}
