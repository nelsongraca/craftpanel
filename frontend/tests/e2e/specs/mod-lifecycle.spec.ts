import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

// fetchModrinthVersions() calls https://api.modrinth.com directly — mocked by the default
// modHandlers (absolute-URL MSW handler), deliberately NOT via page.route: the MSW service
// worker intermittently bypasses Playwright routes → real network → flaky empty result.

test("changes a mod pin strategy to PINNED and saves", async ({page, network}) => {
    let updated: {pin_strategy: string; pinned_version_id?: string} | null = null;
    network.use(
        http.get("/api/servers/srv-1/mods", () =>
            HttpResponse.json({
                mods: [
                    {
                        id: "mod-1",
                        server_id: "srv-1",
                        modrinth_project_id: "worldedit-id",
                        display_name: "WorldEdit",
                        pin_strategy: updated?.pin_strategy ?? "LATEST",
                        pinned_version_id: updated?.pinned_version_id ?? null,
                        installed_version_id: "we-7.3.0",
                        enabled: true,
                        created_at: "2025-01-01T00:00:00Z",
                        updated_at: "2025-01-01T00:00:00Z",
                    },
                ],
            }),
        ),
        http.patch("/api/servers/srv-1/mods/mod-1", async ({request}) => {
            updated = (await request.json()) as typeof updated;
            return HttpResponse.json({});
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Plugins"}).click();

    await page.getByTitle("Change pin strategy").click();

    // The inline strategy select has no accessible name — take the last combobox.
    await page.getByRole("combobox").last().click();
    await page.getByRole("option", {name: "Pinned version"}).click();

    // Picking PINNED loads the Modrinth version list and auto-selects the first entry.
    await expect(page.getByRole("combobox").last()).toContainText("7.3.0", {timeout: 15_000});
    await expect(page.getByRole("button", {name: "Save", exact: true})).toBeEnabled();
    await page.getByRole("button", {name: "Save", exact: true}).click();
    await expect(page.getByText("Pinned: we-7.3.0")).toBeVisible();
});

test("disables and re-enables a mod", async ({page, network}) => {
    let enabled = true;
    const patches: Array<{enabled?: boolean}> = [];
    network.use(
        http.get("/api/servers/srv-1/mods", () =>
            HttpResponse.json({
                mods: [
                    {
                        id: "mod-1",
                        server_id: "srv-1",
                        modrinth_project_id: "worldedit-id",
                        display_name: "WorldEdit",
                        pin_strategy: "LATEST",
                        pinned_version_id: null,
                        installed_version_id: "we-7.3.0",
                        enabled,
                        created_at: "2025-01-01T00:00:00Z",
                        updated_at: "2025-01-01T00:00:00Z",
                    },
                ],
            }),
        ),
        http.patch("/api/servers/srv-1/mods/mod-1", async ({request}) => {
            const body = (await request.json()) as {enabled?: boolean};
            patches.push(body);
            enabled = body.enabled ?? enabled;
            return HttpResponse.json({});
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Plugins"}).click();

    await page.getByRole("switch", {name: "Disable WorldEdit"}).click();
    await expect(page.getByText("Disabled", {exact: true})).toBeVisible();
    expect(patches).toEqual([{enabled: false}]);

    await page.getByRole("switch", {name: "Enable WorldEdit"}).click();
    await expect(page.getByText("Disabled", {exact: true})).not.toBeVisible();
    expect(patches).toEqual([{enabled: false}, {enabled: true}]);
});

test("removes a mod from the installed list", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/servers/srv-1/mods", () =>
            HttpResponse.json({
                mods: deleted
                    ? []
                    : [
                          {
                              id: "mod-1",
                              server_id: "srv-1",
                              modrinth_project_id: "worldedit-id",
                              display_name: "WorldEdit",
                              pin_strategy: "LATEST",
                              pinned_version_id: null,
                              installed_version_id: "we-7.3.0",
                              enabled: true,
                              created_at: "2025-01-01T00:00:00Z",
                              updated_at: "2025-01-01T00:00:00Z",
                          },
                      ],
            }),
        ),
        http.delete("/api/servers/srv-1/mods/:modId", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Plugins"}).click();

    await page.getByTitle("Remove mod").click();
    await expect(page.getByText("No plugins installed")).toBeVisible();
});

test("compatibility check reports compatible mods", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Plugins"}).click();

    await page.getByRole("button", {name: "Check Version"}).click();
    await page.getByRole("button", {name: "Check", exact: true}).click();

    await expect(page.getByText("1/1 plugin compatible with 1.21.5")).toBeVisible();
    await expect(page.getByText("Compatible with 1.21.5", {exact: true})).toBeVisible();
});

test("compatibility check surfaces an error", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/mods/compatibility", () =>
            HttpResponse.json({message: "modrinth unreachable"}, {status: 502}),
        ),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Plugins"}).click();
    await page.getByRole("button", {name: "Check Version"}).click();
    await page.getByRole("button", {name: "Check", exact: true}).click();

    await expect(page.getByText("modrinth unreachable")).toBeVisible();
});
