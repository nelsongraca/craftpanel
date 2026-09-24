import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

// VELOCITY server (srv-proxy) → proxy config. Backend rows show the server's
// display_name as text; the backend_name lives in a text input's value.

test("renders proxy settings, stop command and backends", async ({page}) => {
    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await expect(page.getByText("Proxy Settings", {exact: true})).toBeVisible();
    await expect(page.getByPlaceholder("A Minecraft Proxy")).toBeVisible();
    await expect(page.getByText("Proxy Backends", {exact: true})).toBeVisible();
    await expect(page.getByText("Creative World", {exact: true})).toBeVisible();
});

test("edits proxy settings and saves", async ({page}) => {
    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await page.getByPlaceholder("A Minecraft Proxy").fill("Welcome to the network");
    await expect(page.getByText("Unsaved changes")).toBeVisible();

    await page.getByRole("button", {name: "Save", exact: true}).click();
    await expect(page.getByText("Unsaved changes")).not.toBeVisible();
});

test("changes the forwarding mode via the select", async ({page}) => {
    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    const forwarding = page.getByText("Forwarding Mode", {exact: true}).locator("..");
    await forwarding.getByRole("combobox").click();
    await page.getByRole("option", {name: "LEGACY"}).click();

    await expect(page.getByText("Unsaved changes")).toBeVisible();
});

test("toggles the PROXY protocol setting", async ({page}) => {
    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    const toggle = page.getByRole("checkbox", {name: "PROXY Protocol", exact: true});
    await expect(toggle).not.toBeChecked();
    await toggle.click();
    await expect(toggle).toBeChecked();
    await expect(page.getByText("Unsaved changes")).toBeVisible();
});

test("adds a backend through the modal", async ({page, network}) => {
    let backends = [{id: "backend-1", backend_server_id: "srv-2", backend_name: "creative", order: 0}];
    network.use(
        http.get("/api/servers/srv-proxy/config/proxy", () => HttpResponse.json({backends, forwarding_warnings: []})),
        http.put("/api/servers/srv-proxy/config/proxy", async ({request}) => {
            const body = (await request.json()) as {backends: typeof backends};
            backends = body.backends.map((b, i) => ({...b, order: i}));
            return HttpResponse.json({backends, forwarding_warnings: []});
        }),
    );

    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await page.getByRole("button", {name: "Add Backend", exact: true}).click();
    await expect(page.getByRole("dialog", {name: "Add Backend"})).toBeVisible();

    const name = page.getByPlaceholder("e.g. survival");
    await name.fill("skyblock");
    await page.getByRole("button", {name: "Add", exact: true}).click();

    await expect(page.locator("input[value='skyblock']")).toBeVisible();
    await page.getByRole("button", {name: "Save", exact: true}).click();
    await expect(page.getByText("Unsaved changes")).not.toBeVisible();
});

test("duplicate backend names surface a client-side error", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-proxy/config/proxy", () =>
            HttpResponse.json({
                backends: [
                    {id: "backend-1", backend_server_id: "srv-2", backend_name: "creative", order: 0},
                    {id: "backend-2", backend_server_id: "srv-3", backend_name: "survival", order: 1},
                ],
                forwarding_warnings: [],
            }),
        ),
    );

    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    // Rename the second backend to collide with the first, marking the section dirty.
    await page.locator("table", {hasText: "Backend Name"}).locator("input").nth(1).fill("creative");
    await page.getByRole("button", {name: "Save", exact: true}).click();

    await expect(page.getByText("Backend names must be unique")).toBeVisible();
});

test("removing a backend updates the table", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-proxy/config/proxy", () =>
            HttpResponse.json({
                backends: [{id: "backend-1", backend_server_id: "srv-2", backend_name: "creative", order: 0}],
                forwarding_warnings: [],
            }),
        ),
    );

    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    const row = page.getByRole("row").filter({hasText: "Creative World"});
    await row.getByRole("button").nth(2).click();

    await expect(page.getByText("Creative World", {exact: true})).not.toBeVisible();
    await expect(page.getByText("Unsaved changes")).toBeVisible();
});

test("renders forwarding warnings when the API returns them", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-proxy/config/proxy-settings", () =>
            HttpResponse.json({
                motd: "Proxy",
                max_players: 20,
                forwarding_mode: "MODERN",
                proxy_protocol: false,
                forwarding_warnings: [],
            }),
        ),
        http.put("/api/servers/srv-proxy/config/proxy-settings", () =>
            HttpResponse.json({
                forwarding_warnings: ["Forwarding secret is not set"],
            }),
        ),
    );

    await page.goto("/servers/srv-proxy");
    await page.getByRole("tab", {name: "Configuration"}).click();

    // Warnings are only populated by a save response.
    await page.getByPlaceholder("A Minecraft Proxy").fill("Proxy!");
    await page.getByRole("button", {name: "Save", exact: true}).click();

    await expect(page.getByText("Forwarding secret is not set")).toBeVisible();
});
