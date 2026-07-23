import {test, expect} from "@playwright/test";
import {login} from "./helpers";

test.beforeEach(async ({page}) => {
    await login(page);
});

test("users page loads and lists the admin user", async ({page}) => {
    await page.goto("/users");
    await expect(page.getByRole("heading", {name: "Users"})).toBeVisible();
    await expect(page.getByRole("table")).toBeVisible();
});
