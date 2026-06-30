"use client";

type LivePlayers = { count: number; list: string[] };

export function PlayersPanel({livePlayers}: { livePlayers: LivePlayers | null }) {
    if (!livePlayers || livePlayers.count === 0) return null;

    return (
        <div className="bg-surface border border-border rounded p-4">
            <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-3">
                Online Players ({livePlayers.count})
            </p>
            <div className="flex flex-wrap gap-2">
                {livePlayers.list.map((name) => (
                    <span key={name}
                          className="px-2 py-1 bg-surface-high border border-border rounded text-xs font-mono text-text-primary">
                        {name}
                    </span>
                ))}
            </div>
        </div>
    );
}
