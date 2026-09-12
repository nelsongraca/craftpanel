import { http, HttpResponse } from "msw";

export let fakeExtraPorts = [
    {
        id: "extra-port-1",
        server_id: "srv-1",
        node_id: "node-1",
        name: "Dynmap",
        container_port: 8123,
        host_port: 25501,
        protocol: "TCP",
        created_at: "2026-01-01T00:00:00Z",
        updated_at: "2026-01-01T00:00:00Z",
    },
];

export const portHandlers = [
    http.get("/api/servers/:id/ports", () =>
        HttpResponse.json({
            primary_port: {
                host_port: 25565,
                container_port: 25565,
                protocol: "TCP",
            },
            extra_ports: fakeExtraPorts,
        })
    ),

    http.post("/api/servers/:id/ports", async ({ request }) => {
        const body = (await request.json()) as { name: string; container_port: number; protocol?: string };
        const newPort = {
            id: `extra-port-${Date.now()}`,
            server_id: "srv-1",
            node_id: "node-1",
            name: body.name,
            container_port: body.container_port,
            host_port: 25502,
            protocol: body.protocol ?? "TCP",
            created_at: new Date().toISOString(),
            updated_at: new Date().toISOString(),
        };
        fakeExtraPorts.push(newPort);
        return HttpResponse.json(newPort, { status: 201 });
    }),

    http.delete("/api/servers/:id/ports/:portId", ({ params }) => {
        fakeExtraPorts = fakeExtraPorts.filter((p) => p.id !== params.portId);
        return new HttpResponse(null, { status: 204 });
    }),
];
