import {test, expect} from "@playwright/test";
import {login} from "./helpers";

test("admin can log in", async ({page}) => {
    await login(page);
    await expect(page.getByRole("button", {name: "admin"})).toBeVisible();
});
