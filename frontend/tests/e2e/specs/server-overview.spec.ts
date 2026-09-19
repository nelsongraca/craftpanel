import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("renders server info and live metrics panels", async ({page}) => {
    await page.goto("/servers/srv-1");

    await expect(page.getByRole("heading", {name: "Survival World"})).toBeVisible();
    await expect(page.getByText("Server Info", {exact: true})).toBeVisible();
    await expect(page.getByText("Primary Node", {exact: true}).first()).toBeVisible();
});

test("stops a healthy server", async ({page, network}) => {
    let stopped = false;
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...healthyServer(), status: stopped ? "STOPPING" : "HEALTHY"})
        ),
        http.post("/api/servers/srv-1/stop", () => {
            stopped = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Stop"}).click();

    await expect(page.getByRole("button", {name: "Force Stop"})).toBeVisible();
});

test("starts a stopped server", async ({page, network}) => {
    let started = false;
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...healthyServer(), status: started ? "STARTING" : "STOPPED"})
        ),
        http.post("/api/servers/srv-1/start", () => {
            started = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Start"}).click();

    await expect(page.getByRole("button", {name: "Stop"})).toBeVisible();
});

test("restarts a healthy server", async ({page, network}) => {
    network.use(
        http.post("/api/servers/srv-1/restart", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Restart"}).click();

    await expect(page.getByRole("button", {name: "Restart"})).toBeVisible();
});

test("deletes a stopped server after confirming and redirects", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...healthyServer(), status: "STOPPED"})
        ),
        http.delete("/api/servers/srv-1", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Delete"}).click();
    await expect(page.getByText("Delete Server?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();

    await expect(page).toHaveURL("/servers");
});

test("overflow menu opens the migration tab", async ({page}) => {
    await page.goto("/servers/srv-1");

    await page.locator("button:has(svg.lucide-ellipsis), button:has(svg.lucide-more-horizontal)").first().click();
    await page.getByRole("button", {name: "Migrate"}).click();

    await expect(page.getByRole("tab", {name: "Migration"})).toHaveAttribute("data-active", "");
});

test("export downloads a JSON blob from the overflow menu", async ({page}) => {
    await page.goto("/servers/srv-1");

    await page.locator("button:has(svg.lucide-ellipsis), button:has(svg.lucide-more-horizontal)").first().click();

    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", {name: "Export"}).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toContain(".craftpanel.json");
});

test("shows the restart-pending banner and restarts from it", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...healthyServer(), restart_pending: true})
        ),
        http.post("/api/servers/srv-1/restart", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/servers/srv-1");
    await expect(
        page.getByText("Settings saved. Restart the server for changes to take effect.")
    ).toBeVisible();
    await page.getByRole("button", {name: "Restart Now"}).click();
});

test("renders the not-found state for an unknown server", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1", () => new HttpResponse(null, {status: 404}))
    );

    await page.goto("/servers/srv-1");
    await expect(page.getByText("Server not found.")).toBeVisible();
    await expect(page.getByRole("link", {name: "Back to servers"})).toBeVisible();
});

test("saves general settings from the overview tab", async ({page}) => {
    await page.goto("/servers/srv-1");

    await page.getByTitle("Edit General Settings").click();
    await page.getByPlaceholder("Survival World").fill("Survival Renamed");
    await page.getByRole("button", {name: "Save", exact: true}).first().click();

    await expect(page.getByPlaceholder("Survival World")).not.toBeVisible();
});

test("saves resource settings from the overview tab", async ({page, network}) => {
    let memory = 2048;
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...healthyServer(), memory_mb: memory})
        ),
        http.patch("/api/servers/srv-1/resources", async ({request}) => {
            const body = (await request.json()) as {memory_mb: number};
            memory = body.memory_mb;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");

    await page.getByTitle("Edit Resources").click();
    await page.getByRole("spinbutton").first().fill("4096");
    await page.getByRole("button", {name: "Save", exact: true}).first().click();

    await expect(page.getByText("4096 MB")).toBeVisible();
});

test("saves exposure settings from the overview tab", async ({page, network}) => {
    let subdomain = "survival";
    network.use(
        http.get("/api/servers/srv-1", () =>
            HttpResponse.json({...healthyServer(), public_subdomain: subdomain})
        ),
        http.patch("/api/servers/srv-1/exposure", async ({request}) => {
            const body = (await request.json()) as {public_subdomain: string | null};
            subdomain = body.public_subdomain ?? "";
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");

    await page.getByTitle("Edit Public Access").click();
    await page.getByPlaceholder("myserver").fill("survival2");
    await page.getByRole("button", {name: "Save", exact: true}).first().click();

    await expect(page.getByText("survival2", {exact: true}).first()).toBeVisible();
});

function healthyServer() {
    return {
        id: "srv-1",
        name: "survival",
        display_name: "Survival World",
        description: "Main survival server",
        server_type: "PAPER",
        mc_version: "1.21.5",
        itzg_image_tag: "1.21.5",
        expires_at: null,
        status: "HEALTHY",
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
        config_mode: "MANUAL",
        stop_command: "stop",
        last_player_count: 3,
        last_player_names: ["Steve", "Alex"],
        custom_hostname: null,
        canonical_hostname: null,
        created_at: "2025-01-01T00:00:00Z",
        updated_at: "2025-01-01T00:00:00Z",
    };
}
