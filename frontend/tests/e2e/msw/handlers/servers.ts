import {http, HttpResponse} from "msw";
import {
    fakeServers,
    fakeHealthyServer,
    fakeModSearchHits,
    fakeMigration,
} from "../fixtures/data";

export const serverHandlers = [
    http.get("/api/servers", () => HttpResponse.json(fakeServers)),

    http.get("/api/servers/:id", ({params}) => {
        const server =
            fakeServers.find((s) => s.id === params.id) ?? fakeHealthyServer;
        return HttpResponse.json(server);
    }),

    http.get("/api/servers/:id/mods/search", () =>
        HttpResponse.json({hits: fakeModSearchHits})
    ),

    http.get("/api/servers/:id/migrations", () =>
        HttpResponse.json({migrations: []})
    ),

    http.post("/api/servers/:id/migrations", () =>
        HttpResponse.json(fakeMigration, {status: 202})
    ),

    http.get("/api/migrations/:migrationId", () =>
        HttpResponse.json(fakeMigration)
    ),
];
