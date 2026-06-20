import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

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
