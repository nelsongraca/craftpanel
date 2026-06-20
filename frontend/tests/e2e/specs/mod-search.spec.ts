import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("installed mods load and show pin strategy", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Mods"}).click();

    await expect(page.getByText("WorldEdit", {exact: true})).toBeVisible();
    await expect(page.getByText("Latest stable", {exact: true})).toBeVisible();
});

test("search returns results and Add button appears", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Mods"}).click();

    await page.getByRole("button", {name: /Add Mod/i}).click();
    await page.getByPlaceholder("Search Modrinth…").fill("dynmap");
    await page.getByRole("button", {name: /^Search$/}).click();

    await expect(page.getByText("Dynmap")).toBeVisible();
    await expect(
        page.getByText("A Google Maps-like map for Minecraft servers.")
    ).toBeVisible();
    await expect(page.getByRole("button", {name: "Add", exact: true}).first()).toBeVisible();
});

test("clicking Add then confirming adds mod to installed list", async ({
                                                                           page,
                                                                           network,
                                                                       }) => {
    // Intercept GET mods (empty initially) and POST add + reload response
    let modsState: "empty" | "with-dynmap" = "empty";
    network.use(
        http.get("/api/servers/srv-1/mods", () => {
            if (modsState === "empty") {
                return HttpResponse.json({mods: []});
            }
            return HttpResponse.json({
                mods: [
                    {
                        id: "mod-dynmap",
                        server_id: "srv-1",
                        modrinth_project_id: "dynmap-id",
                        display_name: "Dynmap",
                        pin_strategy: "LATEST",
                        pinned_version_id: null,
                        installed_version_id: null,
                        created_at: "2025-01-01T00:00:00Z",
                        updated_at: "2025-01-01T00:00:00Z",
                    },
                ],
            });
        }),
        http.post("/api/servers/srv-1/mods", async ({request}) => {
            const body = (await request.json()) as {
                modrinth_project_id: string;
                display_name: string;
                pin_strategy: string;
            };
            modsState = "with-dynmap";
            return HttpResponse.json(
                {
                    id: "mod-dynmap",
                    server_id: "srv-1",
                    modrinth_project_id: body.modrinth_project_id,
                    display_name: body.display_name,
                    pin_strategy: body.pin_strategy,
                    pinned_version_id: null,
                    installed_version_id: null,
                    created_at: "2025-01-01T00:00:00Z",
                    updated_at: "2025-01-01T00:00:00Z",
                },
                {status: 201}
            );
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Mods"}).click();
    await expect(page.getByText("No mods installed")).toBeVisible();

    await page.getByRole("button", {name: /Add Mod/i}).click();
    await page.getByPlaceholder("Search Modrinth…").fill("dynmap");
    await page.getByRole("button", {name: /^Search$/}).click();

    await expect(page.getByText("Dynmap")).toBeVisible();

    // Click the Add button next to Dynmap in search results to open confirm form.
    // exact: true needed — "Add Mod" button also matches "Add" without it.
    await page.getByRole("button", {name: "Add", exact: true}).first().click();

    // Confirm panel: select + "Add"/"✕" buttons appear inside Dynmap's row.
    // Use the confirm "Add" (flex-1 class) — unique; EssentialsX still shows its Add button.
    // force: true bypasses viewport scroll-clipping from the max-h-64 container.
    const confirmAddBtn = page.locator("button.flex-1", {hasText: "Add"});
    await expect(confirmAddBtn).toBeVisible({timeout: 3000});
    await confirmAddBtn.click({force: true});

    // After add + reload, search panel closes and mod list shows Dynmap
    await expect(page.getByText("No mods installed")).not.toBeVisible({
        timeout: 5000,
    });
    await expect(page.getByText("Latest stable", {exact: true})).toBeVisible({timeout: 5000});
});
