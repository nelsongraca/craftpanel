import {X} from "lucide-react";

/** A removable tag chip, shared by the config tag editor and the exposure hostname editor. */
export function TagChip({label, onRemove, removeAriaLabel}: {
    label: string;
    onRemove?: () => void;
    removeAriaLabel?: string;
}) {
    return (
        <span className="inline-flex items-center gap-1 rounded border border-border bg-surface-higher px-1.5 py-0.5 font-mono text-xs text-text-primary">
            {label}
            {onRemove && (
                <button
                    type="button"
                    onClick={onRemove}
                    aria-label={removeAriaLabel ?? `Remove ${label}`}
                    className="text-text-muted hover:text-error transition-colors"
                >
                    <X size={11}/>
                </button>
            )}
        </span>
    );
}
