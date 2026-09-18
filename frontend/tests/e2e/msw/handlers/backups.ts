import {http, HttpResponse} from "msw";
import {
    fakeBackups,
    fakeBackupSchedule,
} from "../fixtures/data";
import type {BackupResponse, BackupScheduleResponse} from "@/lib/generated/types.gen";

let backups: BackupResponse[] = [...fakeBackups];
let schedule: BackupScheduleResponse = {...fakeBackupSchedule};

export function resetBackups() {
    backups = [...fakeBackups];
    schedule = {...fakeBackupSchedule};
}

export const backupHandlers = [
    http.get("/api/servers/:id/backups", () =>
        HttpResponse.json({backups})
    ),

    http.post("/api/servers/:id/backups", () => {
        const created: BackupResponse = {
            id: `backup-${Date.now()}`,
            server_id: "srv-1",
            node_id: "node-1",
            trigger: "MANUAL",
            status: "IN_PROGRESS",
            file_path: null,
            size_bytes: null,
            error_message: null,
            created_at: new Date().toISOString(),
            completed_at: null,
        };
        backups = [created, ...backups];
        return HttpResponse.json(created, {status: 202});
    }),

    http.delete("/api/servers/:id/backups/:backupId", ({params}) => {
        backups = backups.filter((b) => b.id !== params.backupId);
        return new HttpResponse(null, {status: 204});
    }),

    http.get("/api/servers/:id/backups/:backupId/download", () => {
        return new HttpResponse("backup-bytes", {
            headers: {"Content-Type": "application/gzip"},
        });
    }),

    http.get("/api/servers/:id/backup-schedule", () =>
        HttpResponse.json(schedule)
    ),

    http.put("/api/servers/:id/backup-schedule", async ({request}) => {
        const body = (await request.json()) as BackupScheduleResponse;
        schedule = body;
        return HttpResponse.json(schedule);
    }),
];
