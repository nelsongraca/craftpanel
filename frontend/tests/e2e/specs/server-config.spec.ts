import {http, HttpResponse} from "msw";
import type {NetworkFixture} from "@msw/playwright";
import {expect, test} from "../fixture";

// PAPER server (srv-1) is MANAGED → game field sections render.
// Field labels are <p> siblings of native <select>/<input> controls; section
// titles are <p> text (CollapsibleTrigger wraps a <div>, not a button).

test("renders mode toggle, stop command and game sections", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await expect(page.getByText("Config Mode", {exact: true})).toBeVisible();
    await expect(page.getByText("Stop Command", {exact: true})).toBeVisible();
    await expect(page.getByText("Gameplay", {exact: true})).toBeVisible();
    await expect(page.getByText("Difficulty", {exact: true})).toBeVisible();
    await expect(page.getByText("JVM Options", {exact: true})).toBeVisible();
});

test("switching config mode to MANUAL hides mapped sections", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await page.getByRole("button", {name: "Switch to Manual"}).click();
    await expect(page.getByText("Disable Managed Env Vars?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();

    await expect(page.getByText("Difficulty", {exact: true})).not.toBeVisible();
    await expect(page.getByRole("button", {name: "Switch to Managed"})).toBeVisible();
});

// Pin the env-vars endpoint so the difficulty baseline is deterministic and cannot leak
// between tests via the default (stateful) handler.
function pinDifficulty(network: NetworkFixture) {
    network.use(
        http.get("/api/servers/srv-1/config/env-vars", () =>
            HttpResponse.json({env_vars: [{key: "DIFFICULTY", value: "normal"}]})
        ),
        http.put("/api/servers/srv-1/config/env-vars", async ({request}) =>
            HttpResponse.json(await request.json())
        )
    );
}

test("editing a field shows the unsaved bar and saves via PUT", async ({page, network}) => {
    pinDifficulty(network);
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    const row = page.getByText("Difficulty", {exact: true}).locator("..").locator("..");
    // Wait for the config to load so the current value is populated before editing.
    await expect(row.getByRole("combobox")).toContainText("normal");
    await row.getByRole("combobox").click();
    await page.getByRole("option", {name: "hard"}).click();

    await expect(page.getByText("Unsaved changes")).toBeVisible();
    await page.getByRole("button", {name: "Save", exact: true}).click();
    await expect(page.getByText("Unsaved changes")).not.toBeVisible();
});

test("discard restores the previously saved value", async ({page, network}) => {
    pinDifficulty(network);
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    const row = page.getByText("Difficulty", {exact: true}).locator("..").locator("..");
    // Wait for the config to load so the current value is populated before editing.
    await expect(row.getByRole("combobox")).toContainText("normal");
    await row.getByRole("combobox").click();
    await page.getByRole("option", {name: "hard"}).click();
    await expect(page.getByText("Unsaved changes")).toBeVisible();

    await page.getByRole("button", {name: "Discard", exact: true}).click();
    await expect(page.getByText("Unsaved changes")).not.toBeVisible();
});

test("adds an extra variable", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await extraVarsAdd(page).click();
    await expect(page.getByPlaceholder("KEY")).toHaveCount(2);
});

test("duplicate env var keys surface a save error", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/config/env-vars", () =>
            HttpResponse.json({
                env_vars: [
                    {key: "DIFFICULTY", value: "normal"},
                    {key: "EXTRA_ONE", value: "x"},
                ],
            })
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await extraVarsAdd(page).click();
    await page.getByPlaceholder("KEY").last().fill("DIFFICULTY");
    await page.getByRole("button", {name: "Save", exact: true}).click();

    await expect(page.getByText("Duplicate env var keys")).toBeVisible();
});

function extraVarsAdd(page: import("@playwright/test").Page) {
    const section = page
        .getByText("Extra Variables", {exact: true})
        .locator("..")
        .locator("..");
    return section.getByRole("button", {name: "Add", exact: true});
}

test("World Type FLAT reveals Generator Settings", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await expect(page.getByText("Generator Settings", {exact: true})).not.toBeVisible();

    const row = page.getByText("World Type", {exact: true}).locator("..").locator("..");
    await row.getByRole("combobox").click();
    await page.getByRole("option", {name: "FLAT"}).click();

    await expect(page.getByText("Generator Settings", {exact: true})).toBeVisible();
});

test("Resource Pack URL reveals the enforce toggle", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await expect(page.getByText("Enforce Resource Pack", {exact: true})).not.toBeVisible();

    const row = page.getByText("Resource Pack URL", {exact: true}).locator("..").locator("..");
    await row.locator("input").fill("https://example.com/pack.zip");

    await expect(page.getByText("Enforce Resource Pack", {exact: true})).toBeVisible();
});

test("Advanced section starts collapsed and expands", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await expect(page.getByText("Custom Server Properties", {exact: true})).not.toBeVisible();
    await page.getByText("Advanced", {exact: true}).click();
    await expect(page.getByText("Custom Server Properties", {exact: true})).toBeVisible();
});

test("saves the stop command", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    const input = page.getByPlaceholder("stop");
    await input.fill("shutdown");
    await expect(page.getByRole("button", {name: "Save", exact: true}).first()).toBeVisible();
});

test("load failure surfaces an error banner", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/config/env-vars", () =>
            HttpResponse.json({message: "agent disconnected"}, {status: 503})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Configuration"}).click();

    await expect(page.getByText("agent disconnected")).toBeVisible();
});
