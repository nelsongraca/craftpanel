"use client";

import type React from "react";

export function HeaderActionButton({
                                       icon,
                                       label,
                                       loading,
                                       onClick,
                                       variant,
                                   }: {
    icon: React.ReactNode;
    label: string;
    loading?: boolean;
    onClick: () => void;
    variant: "green" | "red" | "yellow";
}) {
    const cls = {
        green: "text-healthy  border-healthy/40  hover:bg-healthy/10",
        red: "text-error    border-error/40    hover:bg-error/10",
        yellow: "text-warning  border-warning/40  hover:bg-warning/10",
    }[variant];

    return (
        <button
            onClick={onClick}
            disabled={loading}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded border text-[12px] font-heading font-bold uppercase tracking-widest transition-colors disabled:opacity-40 ${cls}`}
        >
            {loading ? (
                <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin"/>
            ) : (
                icon
            )}
            {label}
        </button>
    );
}
