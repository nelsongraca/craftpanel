import {http, HttpResponse} from "msw";
import {fakeServers, fakeHealthyServer, fakeNode, fakeNetwork} from "../fixtures/data";
import type {ServerResponse} from "@/lib/generated/types.gen";

// Default handlers are read-only projections of the fixtures; mutating tests
// override the affected endpoints via network.use() so nothing leaks.

function find(id: string | readonly string[] | undefined): ServerResponse {
    const sid = Array.isArray(id) ? id[0] : id;
    return fakeServers.find((s) => s.id === sid) ?? fakeHealthyServer;
}

function patched(id: string | readonly string[] | undefined, patch: Partial<ServerResponse>) {
    return {...find(id), ...patch, updated_at: new Date().toISOString()};
}

export const serverDetailHandlers = [
    // Node / network lookups for the detail header
    http.get("/api/nodes/:id", ({params}) => {
        if (params.id === fakeNode.id) return HttpResponse.json(fakeNode);
        return new HttpResponse(null, {status: 404});
    }),

    http.get("/api/networks/:id", () =>
        HttpResponse.json(fakeNetwork)
    ),

    // Lifecycle actions
    http.post("/api/servers/:id/start", ({params}) => HttpResponse.json(patched(params.id, {status: "STARTING"}))),
    http.post("/api/servers/:id/stop", ({params}) => HttpResponse.json(patched(params.id, {status: "STOPPING"}))),
    http.post("/api/servers/:id/restart", ({params}) => HttpResponse.json(patched(params.id, {status: "STARTING"}))),
    http.post("/api/servers/:id/force-stop", ({params}) => HttpResponse.json(patched(params.id, {status: "STOPPED"}))),

    // Edits
    http.patch("/api/servers/:id", async ({params, request}) =>
        HttpResponse.json(patched(params.id, (await request.json()) as Partial<ServerResponse>))
    ),
    http.patch("/api/servers/:id/resources", async ({params, request}) =>
        HttpResponse.json(patched(params.id, (await request.json()) as Partial<ServerResponse>))
    ),
    http.patch("/api/servers/:id/exposure", async ({params, request}) =>
        HttpResponse.json(patched(params.id, (await request.json()) as Partial<ServerResponse>))
    ),
    http.patch("/api/servers/:id/expiration", async ({params, request}) =>
        HttpResponse.json(patched(params.id, (await request.json()) as Partial<ServerResponse>))
    ),
    http.patch("/api/servers/:id/disabled", async ({params, request}) =>
        HttpResponse.json(patched(params.id, (await request.json()) as Partial<ServerResponse>))
    ),
    http.patch("/api/servers/:id/data-dir", () => new HttpResponse(null, {status: 204})),

    // Console logs (ANSI for the log-view path)
    http.get("/api/servers/:id/console/logs", () =>
        HttpResponse.json({
            lines: [
                "\u001b[32m[12:00:00] [main/INFO]: Starting minecraft server\u001b[0m\n",
                "\u001b[31m[12:00:03] [main/FATAL]: Server crashed\u001b[0m\n",
            ],
        })
    ),

    // Export (blob download)
    http.get("/api/servers/:id/export", ({params}) => {
        const server = find(params.id);
        return HttpResponse.json({
            name: server.name,
            server_type: server.server_type,
            mc_version: server.mc_version,
            memory_mb: server.memory_mb,
            cpu_shares: server.cpu_shares,
        });
    }),

    // Delete
    http.delete("/api/servers/:id", () => new HttpResponse(null, {status: 204})),

    // Metrics
    http.get("/api/servers/:id/metrics", ({params}) => {
        const server = find(params.id);
        const t = new Date(Date.now() - 60_000).toISOString();
        return HttpResponse.json({
            server_id: server.id,
            series: {
                cpu_percent: [{t, v: 12}],
                ram_used_mb: [{t, v: 1024}],
                net_in_bytes: [{t, v: 2048}],
                net_out_bytes: [{t, v: 4096}],
            },
        });
    }),
];
