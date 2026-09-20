"use client";

import {useState} from "react";
import {useRouter} from "next/navigation";
import {CopyPlus, Play, RotateCcw, Skull, Square, Trash2} from "lucide-react";
import {deleteServer, forceStopServer, restartServer, startServer, stopServer} from "@/lib/generated/sdk.gen";
import {hasPermission, serverPermissions} from "@/lib/permissions";
import type {Server} from "@/lib/types";
import {serverDisabled} from "@/lib/status";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {IconActionButton} from "@/components/ui/list-table";

export function ServerActions({
                                  server, status, pending, permissions, doAction, doDelete, doDuplicate,
                              }: {
    server: Server;
    status: string;
    pending: string | undefined;
    permissions: string[];
    doAction: (id: string, action: "start" | "stop" | "restart" | "forceStop") => void;
    doDelete: (s: Server) => void;
    doDuplicate: (s: Server) => void;
}) {
    const disabled = serverDisabled(server);
    return (
        <div className="flex items-center justify-end gap-1">
            {status === "STOPPED" && !disabled && hasPermission(permissions, "server.start") && (
                <IconActionButton
                    icon={<Play size={11} strokeWidth={2.5}/>}
                    label="Start"
                    loading={pending === "start"}
                    onClick={() => doAction(server.id, "start")}
                />
            )}
            {(status === "HEALTHY" || status === "STARTING") && hasPermission(permissions, "server.stop") && (
                <IconActionButton
                    icon={<Square size={11} strokeWidth={2.5}/>}
                    label="Stop"
                    loading={pending === "stop"}
                    onClick={() => doAction(server.id, "stop")}
                    danger
                />
            )}
            {status === "STOPPING" && hasPermission(permissions, "server.force_stop") && (
                <IconActionButton
                    icon={<Skull size={11} strokeWidth={2.5}/>}
                    label="Force Stop"
                    loading={pending === "forceStop"}
                    onClick={() => doAction(server.id, "forceStop")}
                    danger
                />
            )}
            {status === "HEALTHY" && !disabled && hasPermission(permissions, "server.restart") && (
                <IconActionButton
                    icon={<RotateCcw size={11} strokeWidth={2.5}/>}
                    label="Restart"
                    loading={pending === "restart"}
                    onClick={() => doAction(server.id, "restart")}
                />
            )}
            {hasPermission(permissions, "server.create") && (
                <IconActionButton
                    icon={<CopyPlus size={11} strokeWidth={2.5}/>}
                    label="Duplicate"
                    onClick={() => doDuplicate(server)}
                />
            )}
            {status === "STOPPED" && hasPermission(permissions, "server.delete") && (
                <IconActionButton
                    icon={<Trash2 size={11} strokeWidth={2.5}/>}
                    label="Delete"
                    onClick={() => doDelete(server)}
                    danger
                />
            )}
        </div>
    );
}

/**
 * Server lifecycle/delete/duplicate actions shared by the server list and the node detail
 * Servers tab. Returns a `renderActions` for SmartList plus the error banner state and the
 * confirm dialog node the host page must render.
 */
export function useServerActions({
                                     permissions,
                                     serverPermissionsMap,
                                     onChanged,
                                 }: {
    permissions: string[];
    serverPermissionsMap: Record<string, string[]>;
    onChanged: () => void;
}) {
    const router = useRouter();
    const [pendingAction, setPendingAction] = useState<Record<string, string>>({});
    const [actionError, setActionError] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    const ACTION_FNS = {
        start: startServer,
        stop: stopServer,
        restart: restartServer,
        forceStop: forceStopServer,
    } as const;

    async function doAction(serverId: string, action: "start" | "stop" | "restart" | "forceStop") {
        setPendingAction((p) => ({...p, [serverId]: action}));
        setActionError(null);
        const {error} = await ACTION_FNS[action]({path: {id: serverId}});
        if (error) {
            setActionError(error.message ?? "Action failed");
        } else {
            onChanged();
        }
        setPendingAction((p) => {
            const n = {...p};
            delete n[serverId];
            return n;
        });
    }

    function doDelete(server: Server) {
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
                    onChanged();
                }
            },
        });
    }

    function doDuplicate(server: Server) {
        router.push(`/servers/new?clone=${server.id}`);
    }

    function renderActions(server: Server) {
        const serverPerms = serverPermissions(permissions, serverPermissionsMap, server.id);
        return (
            <ServerActions
                server={server}
                status={server.status}
                pending={pendingAction[server.id]}
                permissions={serverPerms}
                doAction={doAction}
                doDelete={doDelete}
                doDuplicate={doDuplicate}
            />
        );
    }

    return {renderActions, actionError, setActionError, dialog};
}
