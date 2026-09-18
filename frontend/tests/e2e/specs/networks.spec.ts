import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("lists networks and disables delete when servers exist", async ({page}) => {
    await page.goto("/networks");

    await expect(page.getByRole("heading", {name: "Networks"})).toBeVisible();
    await expect(page.getByText("default", {exact: true}).first()).toBeVisible();
    // fakeNetwork has server_count 4 → delete disabled.
    await expect(
        page.getByRole("button", {name: "Cannot delete: has member servers"})
    ).toBeDisabled();
});

test("creates a network", async ({page, network}) => {
    let created = false;
    network.use(
        http.get("/api/networks", () =>
            HttpResponse.json(
                created
                    ? [{id: "net-new", name: "staging", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-03T00:00:00Z"}]
                    : []
            )
        ),
        http.post("/api/networks", () => {
            created = true;
            return HttpResponse.json(
                {id: "net-new", name: "staging", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-03T00:00:00Z"},
                {status: 201}
            );
        })
    );

    await page.goto("/networks");
    await page.getByRole("button", {name: "New Network", exact: true}).click();

    const dialog = page.getByRole("dialog", {name: "New Network"});
    await dialog.locator("#network-name").fill("staging");
    await dialog.getByRole("button", {name: "Create"}).click();

    await expect(page.getByText("staging", {exact: true}).first()).toBeVisible();
});

test("edits a network", async ({page, network}) => {
    let edited = false;
    network.use(
        http.get("/api/networks", () =>
            HttpResponse.json([
                {id: "net-1", name: edited ? "renamed" : "default", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-01T00:00:00Z"},
            ])
        ),
        http.patch("/api/networks/:id", () => {
            edited = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/networks");
    await page.getByRole("button", {name: "Edit"}).first().click();

    const dialog = page.getByRole("dialog", {name: "Edit Network"});
    await dialog.locator("#network-name").fill("renamed");
    await dialog.getByRole("button", {name: "Save"}).click();

    await expect(page.getByText("renamed", {exact: true}).first()).toBeVisible();
});

test("deletes an empty network after confirming", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/networks", () =>
            HttpResponse.json(
                deleted
                    ? []
                    : [{id: "net-1", name: "default", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-01T00:00:00Z"}]
            )
        ),
        http.delete("/api/networks/:id", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/networks");
    await page.getByRole("button", {name: "Delete"}).click();

    const dialog = page.getByRole("dialog", {name: "Delete Network"});
    await dialog.getByRole("button", {name: "Delete"}).click();
    await expect(page.getByText("No networks yet. Create one to group servers.")).toBeVisible();
});

test("exports a network as a JSON download", async ({page, network}) => {
    network.use(
        http.get("/api/networks", () =>
            HttpResponse.json([
                {id: "net-1", name: "default", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-01T00:00:00Z"},
            ])
        )
    );

    await page.goto("/networks");

    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", {name: "Export"}).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toContain(".json");
});

test("imports a network from a JSON file", async ({page, network}) => {
    let imported = false;
    network.use(
        http.get("/api/networks", () =>
            HttpResponse.json(
                imported
                    ? [{id: "net-new", name: "imported", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-03T00:00:00Z"}]
                    : []
            )
        ),
        http.post("/api/networks/import", () => {
            imported = true;
            return HttpResponse.json(
                {id: "net-new", name: "imported", proxy_port: null, description: null, server_count: 0, created_at: "2025-01-03T00:00:00Z"},
                {status: 201}
            );
        })
    );

    await page.goto("/networks");
    await page.getByRole("button", {name: "Import", exact: true}).click();

    const dialog = page.getByRole("dialog", {name: "Import Network"});
    const payload = JSON.stringify({
        version: 1,
        exported_at: "2025-01-01T00:00:00Z",
        name: "imported",
        description: null,
        proxy_port: null,
        servers: [],
    });
    await dialog.locator('input[type="file"]').setInputFiles({
        name: "network.json",
        mimeType: "application/json",
        buffer: Buffer.from(payload),
    });

    await expect(dialog.getByText("imported", {exact: true})).toBeVisible();
    await dialog.getByRole("button", {name: "Import"}).last().click();

    await expect(page.getByText("imported", {exact: true}).first()).toBeVisible();
});
