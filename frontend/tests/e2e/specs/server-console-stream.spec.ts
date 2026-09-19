import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

// WS mocking via page.routeWebSocket() — NOT MSW ws() handlers (breaks Turbopack HMR).
// xterm renders a hidden textarea (helper) that receives keyboard input.

test("sends a command through the console input", async ({page}) => {
    const received: string[] = [];
    await page.routeWebSocket(/\/api\/ws\/console\//, (ws) => {
        ws.onMessage((msg) => received.push(String(msg)));
        ws.send(JSON.stringify({type: "console.ready"}));
    });

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Console"}).click();

    const terminal = page.locator(".xterm-helper-textarea").last();
    await expect(terminal).toBeAttached({timeout: 5000});
    await terminal.focus();
    await terminal.pressSequentially("say hello");
    await terminal.press("Enter");

    await expect.poll(() => received.join("")).toContain("console.input");
    await expect.poll(() => received.join("")).toContain("say hello");
});

test("renders the ANSI crash log when the server is not healthy", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...stoppedServer()})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Console"}).click();

    await expect(page.getByText("Starting minecraft server")).toBeVisible({timeout: 5000});
    await expect(page.getByText("Server crashed")).toBeVisible();
});

test("ticket failure surfaces an error message", async ({page, network}) => {
    network.use(
        http.post("/api/auth/ws-ticket", () =>
            HttpResponse.json({message: "nope"}, {status: 500})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Console"}).click();

    await expect(page.getByText("Failed to get WebSocket ticket")).toBeVisible({
        timeout: 5000,
    });
});

function stoppedServer() {
    return {
        id: "srv-1",
        name: "survival",
        display_name: "Survival World",
        description: "Main survival server",
        server_type: "PAPER",
        mc_version: "1.21.5",
        itzg_image_tag: "1.21.5",
        expires_at: null,
        status: "STOPPED",
        node_id: "node-1",
        network_id: "net-1",
        host_port: 25565,
        memory_mb: 2048,
        cpu_limit_millicores: 1024,
        exposed_externally: true,
        public_subdomain: "survival",
        is_migrating: false,
        restart_pending: false,
        disabled: false,
        config_mode: "MANAGED",
        stop_command: "stop",
        last_player_count: 3,
        last_player_names: ["Steve", "Alex"],
        custom_hostname: null,
        canonical_hostname: null,
        created_at: "2025-01-01T00:00:00Z",
        updated_at: "2025-01-01T00:00:00Z",
    };
}
