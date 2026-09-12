import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";
import {loginResponse} from "../msw/fixtures/data";

// Login tests need an unauthenticated state — override authRefresh to 401
test.beforeEach(async ({network}) => {
    network.use(
        http.post("/api/auth/refresh", () =>
            HttpResponse.json({message: "No session"}, {status: 401})
        )
    );
});

test("submits credentials and redirects to dashboard on success", async ({
                                                                              page,
                                                                          }) => {
    await page.goto("/login");
    await page.getByPlaceholder("you@example.com").fill("admin@craftpanel.test");
    await page.getByPlaceholder("••••••••").fill("secret");
    await page.getByRole("button", {name: "Sign in"}).click();
    await expect(page).toHaveURL("/");
});

test("shows error message on invalid credentials", async ({
                                                               page,
                                                               network,
                                                           }) => {
    network.use(
        http.post("/api/auth/login", () =>
            HttpResponse.json({message: "Invalid credentials"}, {status: 401})
        )
    );

    await page.goto("/login");
    await page.getByPlaceholder("you@example.com").fill("bad@example.com");
    await page.getByPlaceholder("••••••••").fill("wrong");
    await page.getByRole("button", {name: "Sign in"}).click();

    await expect(page.getByText("Invalid credentials")).toBeVisible();
    await expect(page).toHaveURL("/login");
});

test("trusted device skips TOTP after logout", async ({page, network}) => {
    let loginCount = 0;

    network.use(
        http.post("/api/auth/login", () => {
            loginCount++;
            if (loginCount === 1) {
                return HttpResponse.json({
                    requires_totp: true,
                    temp_token: "fake-temp-token",
                    expires_in: 60,
                });
            }
            return HttpResponse.json(loginResponse);
        }),
        http.post("/api/auth/totp-verify", () =>
            HttpResponse.json(loginResponse)
        ),
    );

    await page.goto("/login");

    // First login — should trigger TOTP challenge
    await page.getByPlaceholder("you@example.com").fill("admin@craftpanel.test");
    await page.getByPlaceholder("••••••••").fill("secret");
    await page.getByRole("button", {name: "Sign in"}).click();

    await expect(page.getByText("Two-factor authentication")).toBeVisible();
    await expect(page.getByText("Trust this device for 30 days")).toBeVisible();

    // Check trust device and submit code
    await page.getByText("Trust this device for 30 days").click();
    await page.getByPlaceholder("123456").fill("123456");
    await page.getByRole("button", {name: "Verify"}).click();

    // Simulate the server setting a device_trust cookie
    await page.context().addCookies([
        {
            name: "device_trust",
            value: "e2e-device-trust-token",
            domain: "localhost",
            path: "/api/auth",
            httpOnly: true,
            sameSite: "Lax",
            expires: Math.floor(Date.now() / 1000) + 30 * 24 * 60 * 60,
        },
    ]);

    // Should land on dashboard
    await expect(page).toHaveURL("/");

    // Logout
    await page.getByRole("button", {name: "admin"}).click();
    await page.getByRole("menuitem", {name: "Sign out"}).click();
    await expect(page).toHaveURL("/login");

    // Second login — should skip TOTP (device is trusted)
    await page.getByPlaceholder("you@example.com").fill("admin@craftpanel.test");
    await page.getByPlaceholder("••••••••").fill("secret");
    await page.getByRole("button", {name: "Sign in"}).click();

    await expect(page).toHaveURL("/");
});
