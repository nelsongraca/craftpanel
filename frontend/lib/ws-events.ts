import type {
    SnapshotPayload,
    NodeMetricsPayload,
    NodeStatusPayload,
    ServerMetricsPayload,
    ServerStatusPayload,
    ServerPlayersPayload,
    BackupProgressPayload,
    BackupCompletePayload,
    AlertPayload,
} from "@/lib/generated/types.gen";

export interface ServerEventMap {
    "snapshot": SnapshotPayload;
    "node.metrics": NodeMetricsPayload;
    "node.status": NodeStatusPayload;
    "server.metrics": ServerMetricsPayload;
    "server.status": ServerStatusPayload;
    "server.players": ServerPlayersPayload;
    "server.backup.progress": BackupProgressPayload;
    "server.backup.complete": BackupCompletePayload;
    "alert.fired": AlertPayload;
    "alert.resolved": AlertPayload;
}
