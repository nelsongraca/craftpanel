import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

// WS mocking uses page.routeWebSocket() directly — NOT MSW ws() handlers.
// Reason: @msw/playwright's ws() handler installs routeWebSocket(MATCH_ALL),
// which intercepts Turbopack's /_next/ HMR WebSocket, breaking React hydration.

test("Connecting status clears on console.ready", async ({page}) => {
    await page.routeWebSocket(/\/api\/ws\/console\//, (ws) => {
        setTimeout(() => {
            ws.send(JSON.stringify({type: "console.ready"}));
        }, 50);
    });

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Console"}).click();

    // statusMsg becomes "" after console.ready → <p> disappears
    await expect(page.getByText("Connecting…")).not.toBeVisible({timeout: 5000});
});

test("console.disconnected shows reason in status", async ({page}) => {
    await page.routeWebSocket(/\/api\/ws\/console\//, (ws) => {
        ws.send(JSON.stringify({type: "console.ready"}));
        setTimeout(() => {
            ws.send(
                JSON.stringify({
                    type: "console.disconnected",
                    reason: "Server stopped",
                })
            );
        }, 80);
    });

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Console"}).click();

    // Match the status <p> exactly (xterm also renders "[Server stopped]" in its span)
    await expect(
        page.locator("p.font-mono", {hasText: "Server stopped"})
    ).toBeVisible({timeout: 5000});
});

test("non-HEALTHY server shows not-running message", async ({
                                                                page,
                                                                network,
                                                            }) => {
    network.use(
        http.get("/api/servers/srv-2", () =>
            HttpResponse.json({
                id: "srv-2",
                name: "creative",
                display_name: "Creative World",
                description: null,
                server_type: "PAPER",
                mc_version: "1.21.5",
                itzg_image_tag: "1.21.5",
                status: "STOPPED",
                node_id: "node-1",
                network_id: "net-1",
                host_port: 25566,
                memory_mb: 1024,
                cpu_shares: 512,
                exposed_externally: false,
                public_subdomain: null,
                is_migrating: false,
                needs_recreate: false,
                config_mode: "MANUAL",
                stop_command: "stop",
                last_player_count: null,
                created_at: "2025-01-01T00:00:00Z",
                updated_at: "2025-01-01T00:00:00Z",
            })
        )
    );

    await page.goto("/servers/srv-2");
    await page.getByRole("button", {name: "Console"}).click();

    await expect(page.getByText("Server is not running")).toBeVisible();
});
