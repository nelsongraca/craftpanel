import {http, HttpResponse} from "msw";
import {
    fakeServers,
    fakeHealthyServer,
    fakeMods,
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

    http.get("/api/servers/:id/mods", () => HttpResponse.json(fakeMods)),

    http.post("/api/servers/:id/mods", async ({request}) => {
        const body = (await request.json()) as {
            modrinth_project_id: string;
            display_name: string;
            pin_strategy: string;
        };
        return HttpResponse.json(
            {
                id: `mod-new-${Date.now()}`,
                server_id: "srv-1",
                modrinth_project_id: body.modrinth_project_id,
                display_name: body.display_name,
                pin_strategy: body.pin_strategy,
                pinned_version_id: null,
                installed_version_id: null,
            },
            {status: 201}
        );
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
