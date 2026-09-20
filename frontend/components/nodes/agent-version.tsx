"use client";

import {AlertTriangle} from "lucide-react";

/**
 * Renders an agent build hash, flagging it when it differs from the running master build.
 * A mismatch means either master or the agent was not redeployed from the same commit.
 */
export function AgentVersion({
                                 version,
                                 masterVersion,
                                 className,
                             }: {
    version?: string | null;
    masterVersion?: string | null;
    className?: string;
}) {
    const mismatch = !!version && !!masterVersion
        && version !== "unknown" && masterVersion !== "unknown"
        && version !== masterVersion;

    return (
        <span className={`inline-flex items-center gap-1 ${className ?? ""}`}>
            {version ?? "-"}
            {mismatch && (
                <span
                    className="text-warning"
                    title={`Agent build differs from master (${masterVersion}) — either master or agent is not updated`}
                >
                    <AlertTriangle size={11} strokeWidth={2.5} className="inline"/>
                </span>
            )}
        </span>
    );
}
