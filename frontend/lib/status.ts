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

// Combined display status derived from lifecycle + health axes
type NodeDisplayStatus = 'ACTIVE' | 'PENDING' | 'REJECTED' | 'DECOMMISSIONED' | 'DEGRADED' | 'UNREACHABLE'

const NODE_DISPLAY_LABELS: Record<NodeDisplayStatus, string> = {
    ACTIVE: 'Active',
    PENDING: 'Pending',
    REJECTED: 'Rejected',
    DECOMMISSIONED: 'Decommissioned',
    DEGRADED: 'Degraded',
    UNREACHABLE: 'Unreachable',
}

const NODE_DISPLAY_CLASSES: Record<NodeDisplayStatus, string> = {
    ACTIVE: 'text-healthy  border border-healthy/30  bg-healthy/10',
    PENDING: 'text-warning  border border-warning/30  bg-warning/10',
    REJECTED: 'text-text-muted border border-border   bg-surface-high',
    DECOMMISSIONED: 'text-text-muted border border-border   bg-surface-high',
    DEGRADED: 'text-warning  border border-warning/30  bg-warning/10',
    UNREACHABLE: 'text-error    border border-error/30    bg-error/10',
}

function toNodeDisplayStatus(status: string, health?: string): NodeDisplayStatus {
    if (status === 'ACTIVE') {
        if (health === 'DEGRADED') return 'DEGRADED'
        if (health === 'UNREACHABLE') return 'UNREACHABLE'
        return 'ACTIVE'
    }
    const lifecycle = ['PENDING', 'REJECTED', 'DECOMMISSIONED']
    return (lifecycle.includes(status) ? status : 'PENDING') as NodeDisplayStatus
}

export function nodeDisplayStatus(status: string, health?: string): NodeDisplayStatus {
    return toNodeDisplayStatus(status, health)
}

export function nodeStatusLabel(status: string, health?: string): string {
    return NODE_DISPLAY_LABELS[toNodeDisplayStatus(status, health)]
}

export function nodeStatusClass(status: string, health?: string): string {
    return NODE_DISPLAY_CLASSES[toNodeDisplayStatus(status, health)]
}
