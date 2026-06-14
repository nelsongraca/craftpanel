// ── Server status ─────────────────────────────────────────────────────────────

type ServerDisplayStatus = 'HEALTHY' | 'UNHEALTHY' | 'STARTING' | 'STOPPING' | 'STOPPED'

const SERVER_STATUS_LABELS: Record<ServerDisplayStatus, string> = {
    HEALTHY: 'Healthy',
    UNHEALTHY: 'Unhealthy',
    STARTING: 'Starting',
    STOPPING: 'Stopping',
    STOPPED: 'Stopped',
}

const SERVER_STATUS_CLASSES: Record<ServerDisplayStatus, string> = {
    HEALTHY: 'text-healthy  border border-healthy/30  bg-healthy/10',
    UNHEALTHY: 'text-error    border border-error/30    bg-error/10',
    STARTING: 'text-warning  border border-warning/30  bg-warning/10',
    STOPPING: 'text-warning  border border-warning/30  bg-warning/10',
    STOPPED: 'text-text-muted border border-border    bg-surface-high',
}

function toServerDisplayStatus(status: string): ServerDisplayStatus {
    return (['HEALTHY', 'UNHEALTHY', 'STARTING', 'STOPPING', 'STOPPED'].includes(status)
        ? status
        : 'STOPPED') as ServerDisplayStatus
}

export function serverStatusLabel(status: string): string {
    return SERVER_STATUS_LABELS[toServerDisplayStatus(status)]
}

export function serverStatusClass(status: string): string {
    return SERVER_STATUS_CLASSES[toServerDisplayStatus(status)]
}

// ── Node status ───────────────────────────────────────────────────────────────

type NodeStatus = 'ACTIVE' | 'PENDING' | 'DEGRADED' | 'REJECTED' | 'DECOMMISSIONED'

const NODE_STATUS_LABELS: Record<NodeStatus, string> = {
    ACTIVE: 'Active',
    PENDING: 'Pending',
    DEGRADED: 'Degraded',
    REJECTED: 'Rejected',
    DECOMMISSIONED: 'Decommissioned',
}

const NODE_STATUS_CLASSES: Record<NodeStatus, string> = {
    ACTIVE: 'text-healthy  border border-healthy/30  bg-healthy/10',
    PENDING: 'text-warning  border border-warning/30  bg-warning/10',
    DEGRADED: 'text-error    border border-error/30    bg-error/10',
    REJECTED: 'text-text-muted border border-border   bg-surface-high',
    DECOMMISSIONED: 'text-text-muted border border-border   bg-surface-high',
}

function toNodeStatus(status: string): NodeStatus {
    return (['ACTIVE', 'PENDING', 'DEGRADED', 'REJECTED', 'DECOMMISSIONED'].includes(status)
        ? status
        : 'PENDING') as NodeStatus
}

export function nodeStatusLabel(status: string): string {
    return NODE_STATUS_LABELS[toNodeStatus(status)]
}

export function nodeStatusClass(status: string): string {
    return NODE_STATUS_CLASSES[toNodeStatus(status)]
}
