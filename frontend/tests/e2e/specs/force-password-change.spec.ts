import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";
import {FAKE_TOKEN, fakeUser} from "../msw/fixtures/data";

test.beforeEach(async ({network}) => {
    network.use(
        http.post("/api/auth/refresh", () =>
            HttpResponse.json({message: "No session"}, {status: 401})
        )
    );
});

test("redirects to force-password-change after login when must_change_password is true", async ({page, network}) => {
    network.use(
        http.post("/api/auth/login", () =>
            HttpResponse.json({access_token: FAKE_TOKEN, expires_in: 900, must_change_password: true})
        ),
        http.get("/api/auth/me", () =>
            HttpResponse.json({...fakeUser, must_change_password: true})
        )
    );

    await page.goto("/login");
    await page.getByPlaceholder("you@example.com").fill("admin@craftpanel.test");
    await page.getByPlaceholder("••••••••").fill("secret");
    await page.getByRole("button", {name: "Sign in"}).click();

    await expect(page).toHaveURL("/force-password-change");
    await expect(page.getByRole("heading", {name: "Change Required"})).toBeVisible();
});

test("submits new password and redirects to dashboard", async ({page, network}) => {
    // auth/me returns must_change_password=true for the first call (during finishAuth)
    // then returns false after the password change to simulate the real backend
    network.use(
        http.post("/api/auth/login", () =>
            HttpResponse.json({access_token: FAKE_TOKEN, expires_in: 900, must_change_password: true})
        ),
        http.post("/api/auth/change-password", () => new HttpResponse(null, {status: 204})),
        http.get("/api/auth/me", () =>
            HttpResponse.json({...fakeUser, must_change_password: true})
        )
    );

    await page.goto("/login");
    await page.getByPlaceholder("you@example.com").fill("admin@craftpanel.test");
    await page.getByPlaceholder("••••••••").fill("secret");
    await page.getByRole("button", {name: "Sign in"}).click();

    await expect(page).toHaveURL("/force-password-change");

    // After login, swap /me handler to return must_change_password=false
    // (as the real backend would after the change-password endpoint)
    const meChanged = http.get("/api/auth/me", () =>
        HttpResponse.json({...fakeUser, must_change_password: false})
    );
    await network.use(meChanged);

    const passwordInputs = page.locator('input[autocomplete="new-password"]');
    await passwordInputs.nth(0).fill("newPass123");
    await passwordInputs.nth(1).fill("newPass123");
    await page.getByRole("button", {name: "Change Password"}).click();

    // After successful password change, the user should be redirected to dashboard
    // BUG: currently the layout re-renders a fresh ForcePasswordChange with done=false
    // because must_change_password=false causes the layout to render Shell>children
    // instead of ForcePasswordChange, losing the done state
    await expect(page).toHaveURL("/", {timeout: 15000});
});