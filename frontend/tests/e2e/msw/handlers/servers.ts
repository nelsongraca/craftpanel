import {http, HttpResponse} from "msw";
import {fakeServers, fakeHealthyServer, fakeModSearchHits, fakeMigration} from "../fixtures/data";

export const serverHandlers = [
    http.get("/api/servers", () => HttpResponse.json(fakeServers)),

    http.get("/api/servers/:id", ({params}) => {
        const server = fakeServers.find((s) => s.id === params.id) ?? fakeHealthyServer;
        return HttpResponse.json(server);
    }),

    http.get("/api/servers/:id/mods/search", () => HttpResponse.json({hits: fakeModSearchHits})),

    http.get("/api/servers/:id/metrics", ({params}) =>
        HttpResponse.json({
            server_id: params.id,
            series: {
                cpu_percent: [],
                ram_used_mb: [],
                net_in_bytes: [],
                net_out_bytes: [],
                block_in_bytes: [],
                block_out_bytes: [],
                heap_used_bytes: [],
                heap_max_bytes: [],
                non_heap_used_bytes: [],
            },
        }),
    ),

    http.get("/api/servers/:id/status-history", ({params}) => HttpResponse.json({server_id: params.id, events: []})),

    http.get("/api/servers/:id/migrations", () => HttpResponse.json({migrations: []})),

    http.post("/api/servers/:id/migrations", () => HttpResponse.json(fakeMigration, {status: 202})),

    http.get("/api/migrations/:migrationId", () => HttpResponse.json(fakeMigration)),
];
