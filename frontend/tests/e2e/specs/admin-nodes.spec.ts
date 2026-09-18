import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

const table = (page: import("@playwright/test").Page) => page.locator("table");

test("lists nodes with columns and status filter", async ({page}) => {
    await page.goto("/nodes");

    await expect(page.getByRole("heading", {name: "Nodes"})).toBeVisible();
    await expect(table(page).getByText("Primary Node", {exact: true})).toBeVisible();
    await expect(table(page).getByText("Secondary Node", {exact: true})).toBeVisible();

    await page.getByRole("combobox").first().click();
    await page.getByRole("option", {name: "Pending"}).click();
    await expect(page.getByText("No nodes match the current filter")).toBeVisible();
});

test("opens the node detail page with tabs", async ({page}) => {
    await page.goto("/nodes");
    await table(page).getByText("Primary Node", {exact: true}).click();

    await expect(page).toHaveURL("/nodes/node-1");
    await expect(page.getByRole("heading", {name: "Primary Node"})).toBeVisible();
    await expect(page.getByRole("tab", {name: /Overview/})).toBeVisible();
});

test("edits a node through the modal", async ({page}) => {
    await page.goto("/nodes");
    await page.getByRole("button", {name: "Edit"}).first().click();

    await expect(page.getByText("Edit Node")).toBeVisible();
    await page.getByLabel("Display Name").fill("Renamed Node");
    await page.getByRole("button", {name: "Save", exact: true}).click();

    await expect(page.getByText("Edit Node")).not.toBeVisible();
});

test("trusts a pending node", async ({page, network}) => {
    network.use(
        http.get("/api/nodes", () => HttpResponse.json([pendingNode()])),
        http.post("/api/nodes/node-9/trust", () => new HttpResponse(null, {status: 204}))
    );

    await page.goto("/nodes");
    await expect(page.getByRole("button", {name: "Trust"}).first()).toBeVisible();
    await page.getByRole("button", {name: "Trust"}).first().click();
});

test("rejects a pending node after confirming", async ({page, network}) => {
    let rejected = false;
    network.use(
        http.get("/api/nodes", () =>
            HttpResponse.json(rejected ? [] : [pendingNode()])
        ),
        http.post("/api/nodes/node-9/reject", () => {
            rejected = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/nodes");
    await page.getByRole("button", {name: "Reject"}).first().click();
    await expect(page.getByText("Reject Node?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();

    await expect(page.getByText("No nodes registered yet", {exact: false})).toBeVisible();
});

test("rotates a node key and shows the token modal", async ({page, network}) => {
    network.use(
        http.post("/api/nodes/node-1/token/rotate", () =>
            HttpResponse.json({node_key: "rotated-node-key-abc123"})
        )
    );

    await page.goto("/nodes");
    await page.getByRole("button", {name: "Rotate Key"}).first().click();
    await expect(page.getByText("Rotate Node Key?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();

    await expect(page.getByText("New Node Key")).toBeVisible();
    await expect(page.getByText("rotated-node-key-abc123")).toBeVisible();
});

test("shuts down an active node after confirming", async ({page, network}) => {
    network.use(
        http.post("/api/nodes/node-1/shutdown", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/nodes");
    await page.getByRole("button", {name: "Shutdown"}).first().click();
    await expect(page.getByText("Shutdown Node?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();
});

test("decommissions a node with no servers", async ({page, network}) => {
    network.use(
        http.get("/api/nodes", () => HttpResponse.json([emptyNode()])),
        http.get("/api/servers", () => HttpResponse.json([]))
    );

    await page.goto("/nodes");
    await page.getByRole("button", {name: "Decommission"}).first().click();
    await expect(page.getByText("Decommission Node?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();
});

test("node detail renders metrics tab with range buttons", async ({page}) => {
    await page.goto("/nodes/node-1");
    await page.getByRole("tab", {name: /Metrics/}).click();

    await expect(page.getByRole("button", {name: "1h", exact: true})).toBeVisible();
    await expect(page.getByText("CPU Utilization")).toBeVisible();
    await page.getByRole("button", {name: "6h", exact: true}).click();
    await expect(page.getByRole("button", {name: "6h", exact: true})).toBeVisible();
});

test("node detail servers tab lists assigned servers", async ({page}) => {
    await page.goto("/nodes/node-1");
    await page.getByRole("tab", {name: /Servers/}).click();

    await expect(table(page).getByText("Survival World")).toBeVisible();
    await expect(page.getByRole("link", {name: "View →"}).first()).toBeVisible();
});

function pendingNode() {
    return {
        ...baseNode(),
        id: "node-9",
        display_name: "Pending Node",
        hostname: "node9.test",
        status: "PENDING",
        health: "UNKNOWN",
    };
}

function emptyNode() {
    return {
        ...baseNode(),
        id: "node-9",
        display_name: "Empty Node",
        hostname: "node9.test",
    };
}

function baseNode() {
    return {
        total_ram_mb: 8192,
        total_cpu_shares: 4096,
        allocated_ram_mb: 2048,
        allocated_cpu_shares: 1024,
        system_ram_used_mb: null,
        system_cpu_percent: null,
        reserved_ram_mb: 1024,
        reserved_cpu_shares: 1024,
        port_range_start: 25565,
        port_range_end: 25600,
        agent_version: "1.0.0",
        description: null,
        domain_suffix: null,
        dns_zone_id: null,
        dns_domain_suffix: null,
        dns_provider_type: null,
        created_at: "2025-01-01T00:00:00Z",
        last_seen_at: "2025-01-01T00:00:00Z",
        id: "node-1",
        display_name: "Primary Node",
        hostname: "node1.test",
        public_ip: "1.2.3.4",
        private_ip: "10.0.0.1",
        status: "ACTIVE",
        health: "HEALTHY",
        updated_at: "2025-01-01T00:00:00Z",
    };
}
