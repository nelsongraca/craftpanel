import {test, expect, type Page} from "@playwright/test";
import {login} from "./helpers";

// Unique per run and namespaced so this can never collide with a real network
// on a live environment that already has servers/networks in it.
const NETWORK_NAME = `smoke-test-network-${Date.now()}`;

test.beforeEach(async ({page}) => {
    await login(page);
    await page.goto("/networks");
});

async function createNetwork(page: Page, name: string) {
    await page.getByRole("button", {name: "New Network"}).click();
    await page.getByRole("dialog", {name: "New Network"}).getByRole("textbox").first().fill(name);
    await page.getByRole("button", {name: "Create"}).click();
    await expect(page.getByRole("cell", {name, exact: true})).toBeVisible();
}

async function deleteNetwork(page: Page, name: string) {
    // Row lookup must be exact: getByRole row/cell name matching is substring-based
    // by default, so a bare `{name}` could match an unrelated real network whose
    // name happens to contain this one. Locate via the exact cell, then act on
    // its row — never delete based on a loose match.
    const cell = page.getByRole("cell", {name, exact: true});
    await expect(cell).toHaveCount(1);
    const row = page.getByRole("row").filter({has: cell});
    await row.getByRole("button", {name: "Delete"}).click();
    await page.getByRole("dialog", {name: "Delete Network"}).getByRole("button", {name: "Delete"}).click();
    await expect(cell).not.toBeVisible();
}

test("network can be created, deleted, and recreated with the same name", async ({page}) => {
    await createNetwork(page, NETWORK_NAME);
    await deleteNetwork(page, NETWORK_NAME);
    await createNetwork(page, NETWORK_NAME);

    // cleanup so repeated smoke runs don't accumulate networks
    await deleteNetwork(page, NETWORK_NAME);
});
