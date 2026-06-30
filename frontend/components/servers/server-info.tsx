import type React from "react";

export function InfoRow({label, value}: { label: string; value: React.ReactNode }) {
    return (
        <div className="flex items-start justify-between gap-4 py-2 border-b border-border last:border-0">
      <span className="text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted shrink-0">
        {label}
      </span>
            <span className="font-mono text-[12px] text-text-primary text-right">{value}</span>
        </div>
    );
}
