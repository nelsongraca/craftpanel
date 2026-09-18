import {http, HttpResponse} from "msw";
import {
    fakeUsers,
    fakeGroups,
    fakeAssignments,
    fakeSystemSettings,
    fakeAlertThresholds,
    fakeAlertEvents,
    fakeNodeMetrics,
    fakeNode,
    fakeNode2,
    fakeNetwork,
} from "../fixtures/data";
import type {
    UserResponse,
    GroupResponse,
    AlertThresholdResponse,
    NodeResponse,
    NetworkResponse,
} from "@/lib/generated/types.gen";

// Default handlers are read-only projections of the fixtures. Tests that need
// mutations override the specific endpoint via network.use() with their own
// closure state, so nothing leaks between tests.

function clone<T>(v: T): T {
    return structuredClone(v);
}

export const adminHandlers = [
    // ── Users ──────────────────────────────────────────────────────────────
    http.get("/api/users", () =>
        HttpResponse.json({users: clone(fakeUsers)})
    ),

    http.post("/api/users", async ({request}) => {
        const body = (await request.json()) as {
            username: string;
            email: string;
            password: string;
            must_change_password?: boolean;
            groups?: string[];
        };
        const created: UserResponse = {
            id: "user-new",
            username: body.username,
            email: body.email,
            is_active: true,
            created_at: new Date().toISOString(),
            must_change_password: body.must_change_password ?? false,
            groups: body.groups ?? [],
            last_login_at: null,
        };
        return HttpResponse.json(created, {status: 201});
    }),

    http.patch("/api/users/:id", async ({request}) => {
        const body = (await request.json()) as Partial<UserResponse>;
        const existing = fakeUsers.find((u) => u.id === "user-1")!;
        return HttpResponse.json({...existing, ...body});
    }),

    http.delete("/api/users/:id", () => new HttpResponse(null, {status: 204})),

    http.put("/api/users/:id/password", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.get("/api/users/:userId/assignments", () =>
        HttpResponse.json({assignments: clone(fakeAssignments)})
    ),

    http.post("/api/users/:userId/assignments", () =>
        HttpResponse.json({id: "assign-new", group_id: "group-2", scope_type: "GLOBAL", scope_id: null}, {status: 201})
    ),

    http.delete("/api/users/:userId/assignments/:assignmentId", () =>
        new HttpResponse(null, {status: 204})
    ),

    // ── Groups ─────────────────────────────────────────────────────────────
    http.get("/api/groups", () => HttpResponse.json(clone(fakeGroups))),

    http.get("/api/groups/:id", ({params}) => {
        const group = fakeGroups.find((g) => g.id === params.id);
        return group
            ? HttpResponse.json(clone(group))
            : new HttpResponse(null, {status: 404});
    }),

    http.post("/api/groups", async ({request}) => {
        const body = (await request.json()) as {name: string};
        const created: GroupResponse = {
            id: "group-new",
            name: body.name,
            is_system: false,
            permissions: [],
            created_at: new Date().toISOString(),
        };
        return HttpResponse.json(created, {status: 201});
    }),

    http.patch("/api/groups/:id", ({params}) => {
        const group = fakeGroups.find((g) => g.id === params.id) ?? fakeGroups[0];
        return HttpResponse.json(clone(group));
    }),

    http.put("/api/groups/:id/permissions", ({params}) => {
        const group = fakeGroups.find((g) => g.id === params.id) ?? fakeGroups[0];
        return HttpResponse.json(clone(group));
    }),

    http.delete("/api/groups/:id", () => new HttpResponse(null, {status: 204})),

    // ── Settings ───────────────────────────────────────────────────────────
    http.get("/api/system/settings", () =>
        HttpResponse.json(clone(fakeSystemSettings))
    ),

    http.patch("/api/system/settings", async ({request}) => {
        const body = (await request.json()) as {
            settings: typeof fakeSystemSettings.settings;
        };
        return HttpResponse.json({
            settings: {...fakeSystemSettings.settings, ...body.settings},
            updated_at: new Date().toISOString(),
            updated_by: "user-1",
        });
    }),

    // ── Alerts ─────────────────────────────────────────────────────────────
    http.get("/api/alerts/thresholds", () =>
        HttpResponse.json({thresholds: clone(fakeAlertThresholds)})
    ),

    http.post("/api/alerts/thresholds", async ({request}) => {
        const body = (await request.json()) as {
            scope_type: string;
            scope_id: string;
            metric: string;
            threshold_value?: number;
            threshold_state?: string;
        };
        const created = {
            id: "threshold-new",
            scope_type: body.scope_type,
            scope_id: body.scope_id,
            metric: body.metric,
            threshold_value: body.threshold_value ?? null,
            threshold_state: body.threshold_state ?? null,
            created_at: new Date().toISOString(),
        } as AlertThresholdResponse;
        return HttpResponse.json(created, {status: 201});
    }),

    http.delete("/api/alerts/thresholds/:id", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.get("/api/alerts/events", () =>
        HttpResponse.json({events: clone(fakeAlertEvents)})
    ),

    // ── Nodes ──────────────────────────────────────────────────────────────
    http.get("/api/nodes/:id/metrics", () =>
        HttpResponse.json(clone(fakeNodeMetrics))
    ),
    http.get("/api/nodes/:id", ({params}) => {
        const node = [fakeNode, fakeNode2].find((n) => n.id === params.id);
        return node
            ? HttpResponse.json(clone(node))
            : new HttpResponse(null, {status: 404});
    }),
    http.post("/api/nodes/:id/trust", () =>
        new HttpResponse(null, {status: 204})
    ),
    http.post("/api/nodes/:id/reject", () =>
        new HttpResponse(null, {status: 204})
    ),
    http.post("/api/nodes/:id/shutdown", () =>
        new HttpResponse(null, {status: 204})
    ),
    http.post("/api/nodes/:id/token/rotate", () =>
        HttpResponse.json({node_key: "rotated-node-key-abc123"})
    ),
    http.patch("/api/nodes/:id", async ({request}) => {
        const body = (await request.json()) as Partial<NodeResponse>;
        return HttpResponse.json({...clone(fakeNode), ...body});
    }),
    http.delete("/api/nodes/:id", () => new HttpResponse(null, {status: 204})),

    // ── Networks ───────────────────────────────────────────────────────────
    http.get("/api/networks", () => HttpResponse.json([clone(fakeNetwork)])),
    http.get("/api/networks/:id", ({params}) => {
        if (params.id === fakeNetwork.id) return HttpResponse.json(clone(fakeNetwork));
        return new HttpResponse(null, {status: 404});
    }),
    http.post("/api/networks", async ({request}) => {
        const body = (await request.json()) as {
            name: string;
            description?: string | null;
        };
        const created: NetworkResponse = {
            id: "net-new",
            name: body.name,
            proxy_port: null,
            description: body.description ?? null,
            server_count: 0,
            created_at: new Date().toISOString(),
        };
        return HttpResponse.json(created, {status: 201});
    }),
    http.patch("/api/networks/:id", () => new HttpResponse(null, {status: 204})),
    http.delete("/api/networks/:id", () => new HttpResponse(null, {status: 204})),
    http.get("/api/networks/:id/export", ({params}) => {
        const network = params.id === fakeNetwork.id ? fakeNetwork : fakeNetwork;
        return HttpResponse.json({
            version: 1,
            exported_at: new Date().toISOString(),
            name: network.name,
            description: network.description,
            proxy_port: network.proxy_port,
            servers: [],
        });
    }),
    http.post("/api/networks/import", async ({request}) => {
        const body = (await request.json()) as {name: string};
        const created: NetworkResponse = {
            id: "net-new",
            name: body.name ?? "imported",
            proxy_port: null,
            description: null,
            server_count: 0,
            created_at: new Date().toISOString(),
        };
        return HttpResponse.json(created, {status: 201});
    }),
];
