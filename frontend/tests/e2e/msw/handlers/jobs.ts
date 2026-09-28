import {http, HttpResponse} from "msw";
import {fakeScheduledJobs} from "../fixtures/data";
import type {CreateScheduledJobRequest, ScheduledJobResponse, UpdateScheduledJobRequest} from "@/lib/generated/types.gen";

let jobs: ScheduledJobResponse[] = [...fakeScheduledJobs];

export function resetScheduledJobs() {
    jobs = [...fakeScheduledJobs];
}

export const jobHandlers = [
    http.get("/api/servers/:id/jobs", () => HttpResponse.json(jobs)),

    http.post("/api/servers/:id/jobs", async ({request}) => {
        const body = (await request.json()) as CreateScheduledJobRequest;
        const created: ScheduledJobResponse = {
            id: `job-${Date.now()}`,
            server_id: "srv-1",
            type: body.type,
            cron_expression: body.cron_expression,
            payload: body.payload ?? null,
            enabled: body.enabled ?? true,
            last_fired_at: null,
        };
        jobs = [...jobs, created];
        return HttpResponse.json(created, {status: 201});
    }),

    http.patch("/api/servers/:id/jobs/:jobId", async ({params, request}) => {
        const body = (await request.json()) as UpdateScheduledJobRequest;
        jobs = jobs.map((job) =>
            job.id === params.jobId
                ? {
                    ...job,
                    cron_expression: body.cron_expression ?? job.cron_expression,
                    payload: body.payload !== undefined ? body.payload : job.payload,
                    enabled: body.enabled ?? job.enabled,
                }
                : job
        );
        return HttpResponse.json(jobs.find((job) => job.id === params.jobId));
    }),

    http.delete("/api/servers/:id/jobs/:jobId", ({params}) => {
        jobs = jobs.filter((job) => job.id !== params.jobId);
        return new HttpResponse(null, {status: 204});
    }),

    http.get("/api/system/job-types", () =>
        HttpResponse.json({types: ["START", "STOP", "RESTART", "RCON_COMMAND"]})
    ),
];
