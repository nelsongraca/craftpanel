import {test, expect} from "@playwright/test";
import {login} from "./helpers";

test.beforeEach(async ({page}) => {
    await login(page);
});

test("nodes page loads and shows at least one node", async ({page}) => {
    await page.goto("/nodes");
    await expect(page.getByRole("heading", {name: "Nodes"})).toBeVisible();
    await expect(page.getByRole("table")).toBeVisible();
});

test("servers page loads", async ({page}) => {
    await page.goto("/servers");
    await expect(page.getByRole("heading", {name: "Servers"})).toBeVisible();
});
