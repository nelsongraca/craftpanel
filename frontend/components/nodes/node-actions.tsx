"use client";

import {useState} from "react";
import {Ban, Check, KeyRound, Pencil, Power, Trash2} from "lucide-react";
import {decommissionNode, rejectNode, rotateNodeToken, shutdownNode, trustNode} from "@/lib/generated/sdk.gen";
import type {Node} from "@/lib/types";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {IconActionButton} from "@/components/ui/list-table";

export type NodeActionKind = "trust" | "reject" | "rotate" | "shutdown" | "decommission";

/**
 * The one status/server-count matrix for node actions. The nodes list and the node detail header
 * both render from this list, so the matrix cannot drift between them. Callers apply their own
 * `canManage` gate.
 */
export function allowedNodeActions(node: Node, serverCount: number): NodeActionKind[] {
    if (node.status === "PENDING") return ["trust", "reject"];
    const actions: NodeActionKind[] = ["rotate"];
    if (node.status === "ACTIVE") actions.push("shutdown");
    if (serverCount === 0 && node.status !== "DECOMMISSIONED") actions.push("decommission");
    return actions;
}

/** Pure renderer for the nodes list: maps an allowed-action list to icon buttons. */
export function NodeActions({
                                actions,
                                pending,
                                onTrust,
                                onReject,
                                onRotate,
                                onShutdown,
                                onDecommission,
                                onEdit,
                            }: {
    actions: NodeActionKind[];
    pending: string | undefined;
    onTrust: () => void;
    onReject: () => void;
    onRotate: () => void;
    onShutdown: () => void;
    onDecommission: () => void;
    onEdit: () => void;
}) {
    const has = (action: NodeActionKind) => actions.includes(action);
    const isPending = has("trust");
    if (actions.length === 0) return null;
    return (
        <div className="flex items-center justify-end gap-1">
            {has("trust") && (
                <button
                    onClick={onTrust}
                    disabled={!!pending}
                    title="Trust node"
                    className="flex items-center gap-1 px-2 py-1 text-xs font-heading font-bold uppercase tracking-wider border rounded-[2px] text-healthy border-healthy/40 hover:bg-healthy/10 transition-colors disabled:opacity-40"
                >
                    {pending === "trust" ? (
                        <span className="w-2.5 h-2.5 border border-current border-t-transparent rounded-full animate-spin"/>
                    ) : (
                        <Check size={11} strokeWidth={2.5}/>
                    )}
                    Trust
                </button>
            )}
            {has("reject") && (
                <button
                    onClick={onReject}
                    disabled={!!pending}
                    title="Reject node"
                    className="flex items-center gap-1 px-2 py-1 text-xs font-heading font-bold uppercase tracking-wider border rounded-[2px] text-error border-error/40 hover:bg-error/10 transition-colors disabled:opacity-40"
                >
                    {pending === "reject" ? (
                        <span className="w-2.5 h-2.5 border border-current border-t-transparent rounded-full animate-spin"/>
                    ) : (
                        <Ban size={11} strokeWidth={2.5}/>
                    )}
                    Reject
                </button>
            )}
            {!isPending && (
                <IconActionButton
                    icon={<Pencil size={11} strokeWidth={2}/>}
                    label="Edit"
                    onClick={onEdit}
                />
            )}
            {has("rotate") && (
                <IconActionButton
                    icon={<KeyRound size={11} strokeWidth={2}/>}
                    label="Rotate Key"
                    loading={pending === "rotate"}
                    onClick={onRotate}
                />
            )}
            {has("shutdown") && (
                <IconActionButton
                    icon={<Power size={11} strokeWidth={2}/>}
                    label="Shutdown"
                    loading={pending === "shutdown"}
                    onClick={onShutdown}
                />
            )}
            {has("decommission") && (
                <IconActionButton
                    icon={<Trash2 size={11} strokeWidth={2}/>}
                    label="Decommission"
                    loading={pending === "decommission"}
                    onClick={onDecommission}
                    danger
                />
            )}
        </div>
    );
}

/**
 * Node lifecycle/identity actions shared by the nodes list and the node detail header. Owns
 * execution, per-node pending state, the confirm dialog, and the allowed-action policy; each
 * context renders the returned actions with its own button style.
 */
export function useNodeActions({
                                   onChanged,
                                   onTokenRotated,
                                   onDecommissioned,
                               }: {
    onChanged: () => void;
    onTokenRotated: (key: string) => void;
    onDecommissioned?: () => void;
}) {
    const [pendingAction, setPendingAction] = useState<Record<string, string>>({});
    const [actionError, setActionError] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    function setPending(nodeId: string, action: string | null) {
        setPendingAction((p) => {
            const n = {...p};
            if (action === null) delete n[nodeId];
            else n[nodeId] = action;
            return n;
        });
    }

    function allowedActions(node: Node, serverCount: number): NodeActionKind[] {
        return allowedNodeActions(node, serverCount);
    }

    async function trust(nodeId: string) {
        setPending(nodeId, "trust");
        setActionError(null);
        const {error} = await trustNode({path: {id: nodeId}});
        if (error) setActionError(error.message ?? "Failed to trust node");
        else onChanged();
        setPending(nodeId, null);
    }

    function reject(nodeId: string) {
        confirm({
            title: "Reject Node?",
            description: "The agent will not be able to connect.",
            destructive: true,
            onConfirm: async () => {
                setPending(nodeId, "reject");
                setActionError(null);
                const {error} = await rejectNode({path: {id: nodeId}});
                if (error) setActionError(error.message ?? "Failed to reject node");
                else onChanged();
                setPending(nodeId, null);
            },
        });
    }

    function rotate(nodeId: string) {
        confirm({
            title: "Rotate Node Key?",
            description: "The agent will need to re-register.",
            onConfirm: async () => {
                setPending(nodeId, "rotate");
                setActionError(null);
                const {error, data} = await rotateNodeToken({path: {id: nodeId}});
                if (error) setActionError(error.message ?? "Failed to rotate key");
                else if (data?.node_key) onTokenRotated(data.node_key);
                setPending(nodeId, null);
            },
        });
    }

    function shutdown(node: Node) {
        confirm({
            title: "Shutdown Node?",
            description: `Send shutdown command to "${node.display_name}"?`,
            onConfirm: async () => {
                setPending(node.id, "shutdown");
                setActionError(null);
                const {error} = await shutdownNode({path: {id: node.id}});
                if (error) setActionError(error.message ?? "Failed to shutdown node");
                else onChanged();
                setPending(node.id, null);
            },
        });
    }

    function decommission(node: Node) {
        confirm({
            title: "Decommission Node?",
            description: `Decommission "${node.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setPending(node.id, "decommission");
                setActionError(null);
                const {error} = await decommissionNode({path: {id: node.id}});
                if (error) setActionError(error.message ?? "Failed to decommission node");
                else (onDecommissioned ?? onChanged)();
                setPending(node.id, null);
            },
        });
    }

    function pendingFor(nodeId: string): string | undefined {
        return pendingAction[nodeId];
    }

    return {
        allowedActions,
        trust,
        reject,
        rotate,
        shutdown,
        decommission,
        pendingFor,
        actionError,
        setActionError,
        dialog,
    };
}
