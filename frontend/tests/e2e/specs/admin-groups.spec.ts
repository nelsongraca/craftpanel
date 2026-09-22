import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("lists groups and locks system groups", async ({page}) => {
    await page.goto("/groups");

    await expect(page.getByRole("heading", {name: "Groups", level: 1})).toBeVisible();
    await expect(page.locator("table").getByText("Super Admin", {exact: true})).toBeVisible();
    await expect(page.locator("table").getByText("Operator", {exact: true})).toBeVisible();

    // System groups expose no Edit/Delete actions.
    const systemRow = page.getByRole("row").filter({hasText: "Super Admin"});
    await expect(systemRow.getByRole("button", {name: "Edit"})).toHaveCount(0);
    await expect(systemRow.getByRole("button", {name: "Delete"})).toHaveCount(0);
});

test("creates a group with permissions", async ({page, network}) => {
    let created = false;
    network.use(
        http.get("/api/groups", () =>
            HttpResponse.json([
                {id: "group-1", name: "Super Admin", is_system: true, permissions: ["*"], created_at: "2025-01-01T00:00:00Z"},
                ...(created
                    ? [{id: "group-new", name: "Custom", is_system: false, permissions: [], created_at: "2025-01-03T00:00:00Z"}]
                    : []),
            ])
        ),
        http.post("/api/groups", () => {
            created = true;
            return HttpResponse.json(
                {id: "group-new", name: "Custom", is_system: false, permissions: [], created_at: "2025-01-03T00:00:00Z"},
                {status: 201}
            );
        }),
        http.put("/api/groups/:id/permissions", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/groups");
    await page.getByRole("button", {name: "New Group"}).click();

    const dialog = page.getByRole("dialog", {name: "New Group"});
    await expect(dialog).toBeVisible();
    await dialog.getByRole("textbox").first().fill("Custom");
    await dialog.getByRole("checkbox", {name: "server.view"}).click();
    await dialog.getByRole("button", {name: "Create"}).click();

    await expect(page.locator("table").getByText("Custom", {exact: true})).toBeVisible();
});

test("edits a non-system group", async ({page, network}) => {
    network.use(
        http.get("/api/groups", () =>
            HttpResponse.json([
                {id: "group-9", name: "Custom", is_system: false, permissions: ["server.view"], created_at: "2025-01-01T00:00:00Z"},
            ])
        ),
        http.patch("/api/groups/:id", () => new HttpResponse(null, {status: 204})),
        http.put("/api/groups/:id/permissions", () => new HttpResponse(null, {status: 204}))
    );

    await page.goto("/groups");
    await page.getByRole("button", {name: "Edit"}).click();

    const dialog = page.getByRole("dialog", {name: "Edit Group"});
    await dialog.getByRole("textbox").first().fill("Custom Renamed");
    await dialog.getByRole("button", {name: "Save"}).click();

    await expect(dialog).not.toBeVisible();
});

test("deletes a non-system group after confirming", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/groups", () =>
            HttpResponse.json(
                deleted
                    ? []
                    : [{id: "group-9", name: "Custom", is_system: false, permissions: [], created_at: "2025-01-01T00:00:00Z"}]
            )
        ),
        http.delete("/api/groups/:id", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/groups");
    await page.getByRole("button", {name: "Delete"}).click();

    const dialog = page.getByRole("alertdialog", {name: "Delete Group"});
    await dialog.getByRole("button", {name: "Delete"}).click();
    await expect(page.getByText("No groups.")).toBeVisible();
});
