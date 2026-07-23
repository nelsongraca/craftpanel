import {test, expect} from "@playwright/test";
import {login} from "./helpers";

test.beforeEach(async ({page}) => {
    await login(page);
});

test("alerts page loads", async ({page}) => {
    await page.goto("/alerts");
    await expect(page.getByRole("heading", {name: "Alerts"})).toBeVisible();
});
