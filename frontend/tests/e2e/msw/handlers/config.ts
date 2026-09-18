import {http, HttpResponse} from "msw";
import {
    fakeEnvVars,
    fakeProxySettings,
    fakeProxyBackends,
} from "../fixtures/data";
import type {
    EnvVarItem,
    ProxyBackendItem,
    ProxySettingsResponse,
} from "@/lib/generated/types.gen";

let envVars: EnvVarItem[] = [...fakeEnvVars];
let settings: ProxySettingsResponse = {...fakeProxySettings};
let backends: ProxyBackendItem[] = [...fakeProxyBackends];

export function resetConfig() {
    envVars = [...fakeEnvVars];
    settings = {...fakeProxySettings};
    backends = [...fakeProxyBackends];
}

export const configHandlers = [
    http.get("/api/servers/:id/config/env-vars", () =>
        HttpResponse.json({env_vars: envVars})
    ),

    http.put("/api/servers/:id/config/env-vars", async ({request}) => {
        const body = (await request.json()) as {env_vars: EnvVarItem[]};
        envVars = body.env_vars;
        return HttpResponse.json({env_vars: envVars});
    }),

    http.get("/api/servers/:id/config/proxy-settings", () =>
        HttpResponse.json(settings)
    ),

    http.put("/api/servers/:id/config/proxy-settings", async ({request}) => {
        const body = (await request.json()) as ProxySettingsResponse;
        settings = {...settings, ...body, forwarding_warnings: []};
        return HttpResponse.json(settings);
    }),

    http.get("/api/servers/:id/config/proxy", () =>
        HttpResponse.json({backends, forwarding_warnings: []})
    ),

    http.put("/api/servers/:id/config/proxy", async ({request}) => {
        const body = (await request.json()) as {backends: ProxyBackendItem[]};
        backends = body.backends.map((b, i) => ({...b, order: i}));
        return HttpResponse.json({backends, forwarding_warnings: []});
    }),

    http.put("/api/servers/:id/config/mode", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.patch("/api/servers/:id/config/stop-command", () =>
        new HttpResponse(null, {status: 204})
    ),
];
