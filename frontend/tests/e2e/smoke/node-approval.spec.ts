import {test, expect} from "@playwright/test";
import {login} from "./helpers";

test.beforeEach(async ({page}) => {
    await login(page);
});

test("pending node can be trusted and turns active", async ({page}) => {
    await page.goto("/nodes");

    const pendingRow = page.getByRole("row", {name: /Pending/}).first();
    const rowCount = await page.getByRole("row").count();
    if (rowCount <= 1 || !(await pendingRow.isVisible().catch(() => false))) {
        test.skip(true, "no pending node to approve");
    }

    await pendingRow.getByRole("button", {name: "Trust"}).click();
    await expect(pendingRow.getByRole("cell", {name: "Active"})).toBeVisible();
});
