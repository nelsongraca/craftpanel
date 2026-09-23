import {http, HttpResponse} from "msw";
import {fakeMods} from "../fixtures/data";
import type {ModResponse} from "@/lib/generated/types.gen";

let mods: ModResponse[] = [...fakeMods.mods];

export function resetMods() {
    mods = [...fakeMods.mods];
}

export const modHandlers = [
    http.get("/api/servers/:id/mods", () => HttpResponse.json({mods})),

    http.post("/api/servers/:id/mods", async ({request, params}) => {
        const body = (await request.json()) as {
            modrinth_project_id: string;
            display_name: string;
            pin_strategy: string;
            pinned_version_id?: string;
        };
        const created: ModResponse = {
            id: `mod-${Date.now()}`,
            server_id: String(params.id),
            modrinth_project_id: body.modrinth_project_id,
            display_name: body.display_name,
            pin_strategy: body.pin_strategy as ModResponse["pin_strategy"],
            pinned_version_id: body.pinned_version_id ?? null,
            installed_version_id: null,
            enabled: true,
            created_at: new Date().toISOString(),
            updated_at: new Date().toISOString(),
        };
        mods = [...mods, created];
        return HttpResponse.json(created, {status: 201});
    }),

    http.patch("/api/servers/:id/mods/:modId", async ({params, request}) => {
        const body = (await request.json()) as {
            pin_strategy?: string;
            pinned_version_id?: string;
            enabled?: boolean;
        };
        const mod = mods.find((m) => m.id === params.modId);
        if (!mod) return new HttpResponse(null, {status: 404});
        if (body.pin_strategy !== undefined) mod.pin_strategy = body.pin_strategy as ModResponse["pin_strategy"];
        if (body.pinned_version_id !== undefined) mod.pinned_version_id = body.pinned_version_id ?? null;
        if (body.enabled !== undefined) mod.enabled = body.enabled;
        return HttpResponse.json(mod);
    }),

    http.delete("/api/servers/:id/mods/:modId", ({params}) => {
        mods = mods.filter((m) => m.id !== params.modId);
        return new HttpResponse(null, {status: 204});
    }),

    http.get("/api/servers/:id/mods/compatibility", () =>
        HttpResponse.json({
            target_version: "1.21.5",
            results: [
                {
                    modrinth_project_id: "worldedit-id",
                    display_name: "WorldEdit",
                    compatible: true,
                    latest_compatible_version_id: "we-7.3.0",
                    latest_compatible_version_number: "7.3.0",
                },
            ],
        }),
    ),

    // The mods tab fetches versions straight from Modrinth (not via master).
    http.get("https://api.modrinth.com/v2/project/:projectId/version", () =>
        HttpResponse.json([
            {
                id: "we-7.3.0",
                version_number: "7.3.0",
                name: "WorldEdit 7.3.0",
                version_type: "release",
                date_published: "2025-01-01T00:00:00Z",
            },
            {
                id: "we-7.2.0",
                version_number: "7.2.0",
                name: "WorldEdit 7.2.0",
                version_type: "beta",
                date_published: "2024-12-01T00:00:00Z",
            },
        ]),
    ),
];
