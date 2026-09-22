"use client";

import {useState} from "react";
import {useRouter} from "next/navigation";
import {CopyPlus, Play, RotateCcw, Skull, Square, Trash2} from "lucide-react";
import {deleteServer, forceStopServer, restartServer, startServer, stopServer} from "@/lib/generated/sdk.gen";
import {hasPermission, scopedPermissions} from "@/lib/permissions";
import type {Server} from "@/lib/types";
import {serverDisabled} from "@/lib/status";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {IconActionButton} from "@/components/ui/list-table";

export type ServerLifecycleAction = "start" | "stop" | "restart" | "forceStop";
export type ServerActionKind = ServerLifecycleAction | "duplicate" | "delete";

const LIFECYCLE_FNS = {
    start: startServer,
    stop: stopServer,
    restart: restartServer,
    forceStop: forceStopServer,
} as const;

/**
 * The one status/permission matrix for server actions. The server list, the node detail Servers tab
 * and the server detail header all render from this list, so the matrix cannot drift between them.
 */
export function allowedServerActions(server: Server, permissions: string[]): ServerActionKind[] {
    const disabled = serverDisabled(server);
    const status = server.status;
    const actions: ServerActionKind[] = [];
    if (status === "STOPPED" && !disabled && hasPermission(permissions, "server.start")) actions.push("start");
    if ((status === "HEALTHY" || status === "STARTING" || status === "UNHEALTHY") && hasPermission(permissions, "server.stop")) actions.push("stop");
    if (status === "STOPPING" && hasPermission(permissions, "server.force_stop")) actions.push("forceStop");
    if (status === "HEALTHY" && !disabled && hasPermission(permissions, "server.restart")) actions.push("restart");
    if (hasPermission(permissions, "server.create")) actions.push("duplicate");
    if (status === "STOPPED" && hasPermission(permissions, "server.delete")) actions.push("delete");
    return actions;
}

/** Pure renderer for the list contexts: maps an allowed-action list to icon buttons. */
export function ServerActions({
                                  actions,
                                  pending,
                                  onAction,
                                  onDelete,
                                  onDuplicate,
                              }: {
    actions: ServerActionKind[];
    pending: string | undefined;
    onAction: (action: ServerLifecycleAction) => void;
    onDelete: () => void;
    onDuplicate: () => void;
}) {
    const iconFor = (action: ServerActionKind) => {
        switch (action) {
            case "start": return <Play size={11} strokeWidth={2.5}/>;
            case "stop": return <Square size={11} strokeWidth={2.5}/>;
            case "forceStop": return <Skull size={11} strokeWidth={2.5}/>;
            case "restart": return <RotateCcw size={11} strokeWidth={2.5}/>;
            case "duplicate": return <CopyPlus size={11} strokeWidth={2.5}/>;
            case "delete": return <Trash2 size={11} strokeWidth={2.5}/>;
        }
    };
    const labelFor = (action: ServerActionKind) => {
        switch (action) {
            case "start": return "Start";
            case "stop": return "Stop";
            case "forceStop": return "Force Stop";
            case "restart": return "Restart";
            case "duplicate": return "Duplicate";
            case "delete": return "Delete";
        }
    };

    return (
        <div className="flex items-center justify-end gap-1">
            {actions.map((action) => (
                <IconActionButton
                    key={action}
                    icon={iconFor(action)}
                    label={labelFor(action)}
                    loading={pending === action}
                    onClick={() => {
                        if (action === "duplicate") onDuplicate();
                        else if (action === "delete") onDelete();
                        else onAction(action);
                    }}
                    danger={action === "stop" || action === "forceStop" || action === "delete"}
                />
            ))}
        </div>
    );
}

/**
 * Server lifecycle/delete/duplicate actions shared by the server list, the node detail Servers tab
 * and the server detail header. Owns execution, per-server pending state, the confirm dialog, and
 * the allowed-action policy; each context renders the returned actions with its own button style.
 */
export function useServerActions({
                                     permissions,
                                     serverPermissionsMap,
                                     onChanged,
                                     onDeleted,
                                 }: {
    permissions: string[];
    serverPermissionsMap: Record<string, string[]>;
    onChanged: () => void;
    onDeleted?: () => void;
}) {
    const router = useRouter();
    const [pendingAction, setPendingAction] = useState<Record<string, string>>({});
    const [actionError, setActionError] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    function allowedActions(server: Server): ServerActionKind[] {
        return allowedServerActions(server, scopedPermissions(permissions, serverPermissionsMap, server.id));
    }

    async function run(serverId: string, action: ServerLifecycleAction) {
        setPendingAction((p) => ({...p, [serverId]: action}));
        setActionError(null);
        const {error} = await LIFECYCLE_FNS[action]({path: {id: serverId}});
        if (error) {
            setActionError(error.message ?? `Failed to ${action} server`);
        } else {
            onChanged();
        }
        setPendingAction((p) => {
            const n = {...p};
            delete n[serverId];
            return n;
        });
    }

    function remove(server: Server) {
        confirm({
            title: "Delete Server?",
            description: `Delete "${server.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setActionError(null);
                const {error} = await deleteServer({path: {id: server.id}});
                if (error) {
                    setActionError(error.message ?? "Failed to delete server");
                } else {
                    (onDeleted ?? onChanged)();
                }
            },
        });
    }

    function duplicate(server: Server) {
        router.push(`/servers/new?clone=${server.id}`);
    }

    function pendingFor(serverId: string): string | undefined {
        return pendingAction[serverId];
    }

    return {allowedActions, run, remove, duplicate, pendingFor, actionError, setActionError, dialog};
}
