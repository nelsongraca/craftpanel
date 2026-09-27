import {http, HttpResponse} from "msw";
import {fakeServers, fakeHealthyServer, fakeModSearchHits, fakeMigration} from "../fixtures/data";

export const serverHandlers = [
    http.get("/api/servers", () => HttpResponse.json(fakeServers)),

    http.get("/api/servers/:id", ({params}) => {
        const server = fakeServers.find((s) => s.id === params.id) ?? fakeHealthyServer;
        return HttpResponse.json(server);
    }),

    http.get("/api/servers/:id/mods/search", () => HttpResponse.json({hits: fakeModSearchHits})),

    http.get("/api/servers/:id/metrics", ({params}) => {
        const t = new Date(Date.now() - 60_000).toISOString();
        return HttpResponse.json({
            server_id: params.id,
            series: {
                cpu_percent: [{t, v: 12}],
                ram_used_mb: [{t, v: 1024}],
                net_in_bytes: [{t, v: 2048}],
                net_out_bytes: [{t, v: 4096}],
                block_in_bytes: [],
                block_out_bytes: [],
                heap_used_bytes: [{t, v: 512 * 1024 * 1024}],
                heap_max_bytes: [{t, v: 1536 * 1024 * 1024}],
                non_heap_used_bytes: [{t, v: 128 * 1024 * 1024}],
            },
        });
    }),

    http.get("/api/servers/:id/status-history", ({params}) => HttpResponse.json({server_id: params.id, events: []})),

    http.get("/api/servers/:id/migrations", () => HttpResponse.json({migrations: []})),

    http.post("/api/servers/:id/migrations", () => HttpResponse.json(fakeMigration, {status: 202})),

    http.get("/api/migrations/:migrationId", () => HttpResponse.json(fakeMigration)),
];
