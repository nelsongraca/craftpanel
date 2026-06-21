import {expect, test} from "../fixture";

// authRefresh default handler returns 200 → auto-authenticated on any page load
// Server names render in both the desktop table and the (md:hidden) mobile card
// list, so scope text assertions to the table to avoid strict-mode double matches.

test("renders server rows with mixed statuses", async ({page}) => {
    await page.goto("/servers");
    const table = page.locator("table");
    await expect(table.getByText("Survival World")).toBeVisible();
    await expect(table.getByText("Creative World")).toBeVisible();
    await expect(table.getByText("Skyblock")).toBeVisible();
    await expect(table.getByText("Lobby")).toBeVisible();

    // Status badges (use row context to avoid matching hidden <option> elements)
    const rows = page.locator("tbody tr");
    await expect(rows.filter({hasText: "Healthy"}).first()).toBeVisible();
    await expect(rows.filter({hasText: "Stopped"}).first()).toBeVisible();
    await expect(rows.filter({hasText: "Unhealthy"}).first()).toBeVisible();
    await expect(rows.filter({hasText: "Starting"}).first()).toBeVisible();
});

test("search narrows server list", async ({page}) => {
    await page.goto("/servers");
    const table = page.locator("table");
    await expect(table.getByText("Survival World")).toBeVisible();

    await page.getByPlaceholder("Search servers…").fill("creative");
    await expect(table.getByText("Creative World")).toBeVisible();
    await expect(table.getByText("Survival World")).not.toBeVisible();
    await expect(table.getByText("Skyblock")).not.toBeVisible();
});

test("status filter narrows server list", async ({page}) => {
    await page.goto("/servers");
    const table = page.locator("table");
    await expect(table.getByText("Survival World")).toBeVisible();

    await page.getByRole("combobox").first().selectOption("STOPPED");

    await expect(table.getByText("Creative World")).toBeVisible();
    await expect(table.getByText("Survival World")).not.toBeVisible();
    await expect(table.getByText("Skyblock")).not.toBeVisible();
});

test("node filter narrows list to servers on selected node", async ({
                                                                        page,
                                                                    }) => {
    await page.goto("/servers");
    const table = page.locator("table");
    await expect(table.getByText("Survival World")).toBeVisible();

    const nodeSelect = page.getByRole("combobox").filter({hasText: "All Nodes"});
    await nodeSelect.selectOption({label: "Secondary Node"});

    await expect(table.getByText("Lobby")).toBeVisible();
    await expect(table.getByText("Survival World")).not.toBeVisible();
});
