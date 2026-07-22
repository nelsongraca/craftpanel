import {test, expect} from "@playwright/test";
import {login} from "./helpers";

const NETWORK_NAME = `smoke-net-${Date.now()}`;

test.beforeEach(async ({page}) => {
    await login(page);
    await page.goto("/networks");
});

async function createNetwork(page: import("@playwright/test").Page, name: string) {
    await page.getByRole("button", {name: "New Network"}).click();
    await page.getByRole("dialog", {name: "New Network"}).getByRole("textbox").first().fill(name);
    await page.getByRole("button", {name: "Create"}).click();
    await expect(page.getByRole("cell", {name, exact: true})).toBeVisible();
}

async function deleteNetwork(page: import("@playwright/test").Page, name: string) {
    const row = page.getByRole("row", {name});
    await row.getByRole("button", {name: "Delete"}).click();
    await page.getByRole("dialog", {name: "Delete Network"}).getByRole("button", {name: "Delete"}).click();
    await expect(page.getByRole("cell", {name, exact: true})).not.toBeVisible();
}

test("network can be created, deleted, and recreated with the same name", async ({page}) => {
    await createNetwork(page, NETWORK_NAME);
    await deleteNetwork(page, NETWORK_NAME);
    await createNetwork(page, NETWORK_NAME);

    // cleanup so repeated smoke runs don't accumulate networks
    await deleteNetwork(page, NETWORK_NAME);
});
