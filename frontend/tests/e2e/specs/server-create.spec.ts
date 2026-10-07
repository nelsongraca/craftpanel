import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("renders the create form with sections and defaults", async ({page}) => {
    await page.goto("/servers/new");

    await expect(page.getByRole("heading", {name: "New Server"})).toBeVisible();
    await expect(page.getByText("Identity", {exact: true})).toBeVisible();
    await expect(page.getByRole("main").getByText("Software", {exact: true})).toBeVisible();
    await expect(page.getByRole("main").getByText("Infrastructure", {exact: true})).toBeVisible();
    await expect(page.locator("#server-name")).toBeVisible();
    // First ACTIVE node is auto-selected (FieldSelect renders a combobox trigger, not a native input).
    await expect(page.locator("#node")).toContainText("Primary Node");
});

test("creates a server and redirects to its detail page", async ({page, network}) => {
    network.use(http.post("/api/servers", () => HttpResponse.json({id: "srv-1"}, {status: 201})));

    await page.goto("/servers/new");
    await page.locator("#server-name").fill("newserver");
    await page.getByRole("button", {name: "Create Server"}).click();

    await expect(page).toHaveURL("/servers/srv-1");
});

test("shows validation errors from the API", async ({page, network}) => {
    network.use(http.post("/api/servers", () => HttpResponse.json({message: "Name already in use"}, {status: 409})));

    await page.goto("/servers/new");
    await page.locator("#server-name").fill("existing");
    await page.getByRole("button", {name: "Create Server"}).click();

    await expect(page.getByText("Name already in use")).toBeVisible();
});

test("clone mode prefills from the source server and hides software fields", async ({page}) => {
    await page.goto("/servers/new?clone=srv-1");

    await expect(page.getByRole("heading", {name: "Clone Server"})).toBeVisible();
    await expect(page.getByRole("button", {name: "Clone Server"})).toBeVisible();
    // Clone prefills the display name from the source; the internal name stays blank for the user to set.
    await expect(page.locator("#display-name")).toHaveValue("Survival World");
    await expect(page.locator("#server-name")).toHaveValue("");
    // Software is always copied from the source, so those fields are not rendered.
    await expect(page.getByRole("main").getByText("Software", {exact: true})).toHaveCount(0);
    await expect(page.locator("#server-type")).toHaveCount(0);
    await expect(page.locator("#itzg-image-tag")).toHaveCount(0);
    // Resources stay editable and are prefilled from the source.
    await expect(page.locator("#ram-mb")).toHaveValue("2048");
});

test("clone mode submits resource overrides and redirects", async ({page, network}) => {
    let body: Record<string, unknown> | undefined;
    network.use(
        http.post("/api/servers/srv-1/clone", async ({request}) => {
            body = (await request.json()) as Record<string, unknown>;
            return HttpResponse.json({id: "srv-1"}, {status: 201});
        }),
    );

    await page.goto("/servers/new?clone=srv-1");
    await page.locator("#server-name").fill("survival-copy");
    await page.locator("#ram-mb").fill("4096");
    await page.getByRole("button", {name: "Clone Server"}).click();

    await expect(page).toHaveURL("/servers/srv-1");
    expect(body).toMatchObject({
        name: "survival-copy",
        memory_mb: 4096,
        node_id: "node-1",
    });
    // Network is unchanged from the source, so it is not sent as an override.
    expect(body).not.toHaveProperty("network_id");
});
