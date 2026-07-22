import {test, expect} from "@playwright/test";

const email = process.env.SMOKE_ADMIN_EMAIL;
const password = process.env.SMOKE_ADMIN_PASSWORD;

test.beforeEach(async ({page}) => {
    if (!email || !password) {
        throw new Error("SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD must be set");
    }
    await page.goto("/login");
    await page.getByRole("textbox", {name: "you@example.com"}).fill(email);
    await page.getByRole("textbox", {name: "••••••••"}).fill(password);
    await page.getByRole("button", {name: "Sign in"}).click();
    await expect(page).toHaveURL("/");
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
