import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("renders profile sections and TOTP status", async ({page}) => {
    await page.goto("/account");

    await expect(page.getByRole("heading", {name: "Account"})).toBeVisible();
    await expect(page.getByRole("heading", {name: "Password"})).toBeVisible();
    await expect(page.getByText("TOTP is disabled")).toBeVisible();
});

test("changes the password", async ({page, network}) => {
    network.use(
        http.post("/api/auth/change-password", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/account");
    const passwordInputs = page.locator('input[type="password"]');
    await passwordInputs.nth(0).fill("oldpass123");
    await passwordInputs.nth(1).fill("newpass123");
    await passwordInputs.nth(2).fill("newpass123");
    await page.getByRole("button", {name: "Change Password"}).click();

    await expect(page.getByRole("button", {name: "Change password again"})).toBeVisible();
});

test("shows a mismatch error", async ({page}) => {
    await page.goto("/account");
    const passwordInputs = page.locator('input[type="password"]');
    await passwordInputs.nth(0).fill("oldpass123");
    await passwordInputs.nth(1).fill("newpass123");
    await passwordInputs.nth(2).fill("different");
    await page.getByRole("button", {name: "Change Password"}).click();

    await expect(page.getByText("Passwords do not match")).toBeVisible();
});

test("signs out all other sessions", async ({page, network}) => {
    network.use(
        http.post("/api/auth/logout-all", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/account");
    await page.getByRole("button", {name: "Sign out all other sessions"}).click();

    await expect(
        page.getByText("All other sessions have been signed out.")
    ).toBeVisible();
});

test("opens the TOTP setup modal and enables 2FA", async ({page, network}) => {
    network.use(
        http.post("/api/auth/totp/setup", () =>
            HttpResponse.json({
                secret: "JBSWY3DPEHPK3PXP",
                qr_code: "data:image/png;base64,iVBORw0KGgo=",
            })
        ),
        http.post("/api/auth/totp/enable", () =>
            HttpResponse.json({recovery_codes: ["code-1", "code-2"]})
        )
    );

    await page.goto("/account");
    await page.getByRole("button", {name: "Set up TOTP"}).click();

    const dialog = page.getByRole("dialog", {name: "Enable TOTP"});
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("JBSWY3DPEHPK3PXP")).toBeVisible();

    const submit = dialog.getByRole("button", {name: "Enable TOTP"});
    await expect(submit).toBeDisabled();
    await dialog.getByPlaceholder("123456").fill("123456");
    await expect(submit).toBeEnabled();
    await submit.click();

    await expect(page.locator("textarea[aria-label='Recovery codes']")).toBeVisible();
    await dialog.getByRole("button", {name: "Done"}).click();
});

test("disables TOTP with a verification code", async ({page, network}) => {
    let enabled = true;
    network.use(
        http.get("/api/auth/totp/status", () =>
            HttpResponse.json({enabled, recovery_codes_remaining: enabled ? 2 : 0})
        ),
        http.post("/api/auth/totp/disable", () => {
            enabled = false;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/account");
    await expect(page.getByText("TOTP is enabled")).toBeVisible();

    await page.getByRole("button", {name: "Disable TOTP"}).click();
    await page.getByPlaceholder("123456").fill("654321");
    await page.getByRole("button", {name: "Disable 2FA"}).click();

    await expect(page.getByText("TOTP is disabled")).toBeVisible();
});
