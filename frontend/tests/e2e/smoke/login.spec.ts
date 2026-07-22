import {test, expect} from "@playwright/test";

const email = process.env.SMOKE_ADMIN_EMAIL;
const password = process.env.SMOKE_ADMIN_PASSWORD;

test("admin can log in", async ({page}) => {
    if (!email || !password) {
        throw new Error("SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD must be set");
    }

    await page.goto("/login");
    await page.getByRole("textbox", {name: "you@example.com"}).fill(email);
    await page.getByRole("textbox", {name: "••••••••"}).fill(password);
    await page.getByRole("button", {name: "Sign in"}).click();

    await expect(page).toHaveURL("/");
    await expect(page.getByRole("button", {name: "admin"})).toBeVisible();
});
