import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("renders stacked settings sections and saves", async ({page}) => {
    await page.goto("/settings");

    await expect(page.getByRole("heading", {name: "Settings"})).toBeVisible();
    await expect(page.getByRole("heading", {name: "Branding"})).toBeVisible();
    await expect(page.getByRole("heading", {name: "Console"})).toBeVisible();

    await page.getByPlaceholder("CraftPanel").fill("My Panel");
    await page.getByRole("button", {name: "Save Settings"}).click();

    await expect(page.getByText("Settings saved.")).toBeVisible();
});

test("shows a load failure message", async ({page, network}) => {
    network.use(
        http.get("/api/system/settings", () =>
            HttpResponse.json({message: "boom"}, {status: 500})
        )
    );

    await page.goto("/settings");
    await expect(page.getByText("Failed to load settings.")).toBeVisible();
});

test("denies access without system.settings", async ({page, network}) => {
    network.use(
        http.get("/api/auth/me", () =>
            HttpResponse.json({
                id: "user-1",
                username: "admin",
                email: "admin@craftpanel.test",
                groups: ["Viewer"],
                permissions: ["server.view"],
                server_permissions: {},
                network_permissions: {},
                totp_enabled: false,
            })
        )
    );

    await page.goto("/settings");
    await expect(
        page.getByText("You do not have permission to view or edit system settings.")
    ).toBeVisible();
});
