"use client";

type LivePlayers = { count: number; list: string[] };

export function PlayersPanel({livePlayers}: { livePlayers: LivePlayers | null }) {
    if (!livePlayers || livePlayers.count === 0) return null;

    // Names can repeat (servers hide a player's identity as "Anonymous Player"), which would
    // otherwise produce duplicate React keys and chips.
    const names = Array.from(new Set(livePlayers.list));

    return (
        <div className="bg-surface border border-border rounded p-4">
            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-3">
                Online Players ({livePlayers.count})
            </p>
            <div className="flex flex-wrap gap-2">
                {names.map((name) => (
                    <span key={name}
                          className="px-2 py-1 bg-surface-high border border-border rounded text-xs font-mono text-text-primary">
                        {name}
                    </span>
                ))}
            </div>
        </div>
    );
}
