"use client";

/** The Discard/Save strip shown under a config section while there are unsaved changes. */
export function UnsavedBar({
                               onDiscard,
                               onSave,
                               saving,
                           }: {
    onDiscard: () => void;
    onSave: () => void;
    saving: boolean;
}) {
    return (
        <div className="flex items-center justify-between pt-2 border-t border-border">
            <span className="text-xs text-text-muted">Unsaved changes</span>
            <div className="flex gap-2">
                <button
                    onClick={onDiscard}
                    className="px-3 py-1.5 rounded text-xs font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                >
                    Discard
                </button>
                <button
                    onClick={onSave}
                    disabled={saving}
                    className="px-3 py-1.5 rounded text-xs font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                >
                    {saving ? "Saving\u2026" : "Save"}
                </button>
            </div>
        </div>
    );
}
