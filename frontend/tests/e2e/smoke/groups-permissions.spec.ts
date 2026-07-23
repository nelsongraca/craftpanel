import {test, expect} from "@playwright/test";
import {login} from "./helpers";

// Unique per run and namespaced so this can never collide with a real group
// on a live environment that already has groups in it.
const GROUP_NAME = `smoke-test-group-${Date.now()}`;

test.beforeEach(async ({page}) => {
    await login(page);
    await page.goto("/groups");
});

test("group can be created with a permission, listed, and deleted", async ({page}) => {
    await page.getByRole("button", {name: "New Group"}).click();
    const dialog = page.getByRole("dialog", {name: "New Group"});
    await dialog.getByRole("textbox").fill(GROUP_NAME);
    await dialog.getByRole("checkbox", {name: "server.view"}).check();
    await dialog.getByRole("button", {name: "Create"}).click();

    // Row lookup must be exact: getByRole row/cell name matching is substring-based
    // by default, so a bare `{name}` could match an unrelated real group whose name
    // happens to contain this one.
    const cell = page.getByRole("cell", {name: GROUP_NAME, exact: true});
    await expect(cell).toBeVisible();
    await expect(cell).toHaveCount(1);
    const row = page.getByRole("row").filter({has: cell});
    await expect(row.getByRole("cell", {name: "server.view"})).toBeVisible();

    await row.getByRole("button", {name: "Delete"}).click();
    await page.getByRole("dialog", {name: "Delete Group"}).getByRole("button", {name: "Delete"}).click();
    await expect(cell).not.toBeVisible();
});
