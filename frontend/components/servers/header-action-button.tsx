"use client";

import type React from "react";

export function HeaderActionButton({
                                       icon,
                                       label,
                                       loading,
                                       onClick,
                                       variant,
                                       disabled,
                                   }: {
    icon: React.ReactNode;
    label: string;
    loading?: boolean;
    onClick: () => void;
    variant: "green" | "red" | "yellow" | "amber" | "default";
    disabled?: boolean;
}) {
    const cls = {
        green: "text-healthy  border-healthy/40  hover:bg-healthy/10",
        red: "text-error    border-error/40    hover:bg-error/10",
        yellow: "text-warning  border-warning/40  hover:bg-warning/10",
        amber: "text-warning  border-warning/40  hover:bg-warning/10",
        default: "text-text-muted border-border   hover:bg-surface-high hover:text-text-primary",
    }[variant];

    return (
        <button
            onClick={onClick}
            disabled={loading || disabled}
            title={label}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded border text-xs font-heading font-bold uppercase tracking-widest transition-colors disabled:opacity-40 ${cls}`}
        >
            {loading ? (
                <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin"/>
            ) : (
                icon
            )}
            {/* Label hidden on phones (icon-only) to keep the action row from overflowing */}
            <span className="hidden sm:inline">{label}</span>
        </button>
    );
}
