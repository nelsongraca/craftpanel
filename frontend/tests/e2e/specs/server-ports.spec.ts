import { expect, test } from "../fixture";

test("displays primary port and extra ports in Ports tab", async ({ page }) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", { name: "Ports" }).click();

    await expect(page.getByText("Primary Server Port")).toBeVisible();
    await expect(page.getByText("25565").first()).toBeVisible();
    await expect(page.getByRole("cell", { name: "Dynmap", exact: true })).toBeVisible();
    await expect(page.getByRole("cell", { name: "8123", exact: true })).toBeVisible();
});

test("can open add port modal, submit form, and display new port", async ({ page }) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", { name: "Ports" }).click();

    await page.getByRole("button", { name: /Add Extra Port/i }).click();
    await expect(page.getByRole("heading", { name: "Add Extra Port" })).toBeVisible();

    await page.getByPlaceholder("e.g. Dynmap, Geyser, Votifier").fill("Votifier");
    await page.getByPlaceholder("e.g. 8123, 19132").fill("8192");
    await page.getByRole("button", { name: "Add Port", exact: true }).click();

    await expect(page.getByRole("cell", { name: "Votifier", exact: true })).toBeVisible();
    await expect(page.getByRole("cell", { name: "8192", exact: true })).toBeVisible();
});
