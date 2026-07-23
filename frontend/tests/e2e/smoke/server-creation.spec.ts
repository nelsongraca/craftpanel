import {test, expect, type Page, type Browser, type BrowserContext} from "@playwright/test";
import {login} from "./helpers";

// Refresh tokens rotate on every use (old one revoked) — logging in fresh per test
// in the same browser context races token rotation and randomly 401s. One shared
// login for the whole file avoids that; tests still run serially against real state
// (each depends on the network/servers earlier tests created).
test.describe.configure({mode: "serial"});
// Real container start/stop can take well over the default 30s test timeout.
test.setTimeout(180_000);

// Unique per run and namespaced so these can never collide with a real server or
// network on a live environment that already has its own servers/networks.
const RUN_ID = Date.now();
const NETWORK_NAME = `smoke-test-network-${RUN_ID}`;
const FABRIC_NAME = `smoke-test-fabric-${RUN_ID}`;
const PAPER_NAME = `smoke-test-paper-${RUN_ID}`;
const VELOCITY_NAME = `smoke-test-velocity-${RUN_ID}`;

let context: BrowserContext;
let page: Page;

// Row lookup helper: getByRole name matching is substring-based by default, so a
// bare `{name}` risks matching an unrelated real server/network whose name happens
// to contain this one. Always locate via the exact cell, then scope to its row.
function rowByExactName(p: Page, name: string) {
    return p.getByRole("row").filter({has: p.getByRole("cell", {name, exact: true})});
}

async function createServer(
    p: Page,
    {name, type, network}: {name: string; type: string; network?: string},
) {
    await p.goto("/servers/new");
    await p.getByLabel("Name*", {exact: true}).fill(name);
    await p.getByLabel("Server Type").selectOption(type);
    if (network) {
        await p.getByLabel("Network").selectOption(network);
    }
    await p.getByRole("button", {name: "Create Server"}).click();
    await expect(p).toHaveURL(/\/servers\/[0-9a-f-]+$/);
}

async function deleteServerByName(p: Page, name: string) {
    await p.goto("/servers");
    const cell = p.getByRole("cell", {name, exact: true});
    // Locator.count()/isVisible() don't auto-wait or retry — they're one-shot
    // snapshots that can read 0/false before the client-rendered table finishes
    // loading, silently skipping a delete that should have happened. expect(...)
    // polls until the timeout, so use that to distinguish "not rendered yet" from
    // "genuinely absent."
    const found = await expect(cell).toBeVisible({timeout: 5000}).then(() => true).catch(() => false);
    if (!found) return;
    await expect(cell).toHaveCount(1);
    await rowByExactName(p, name).getByRole("button", {name: "Delete"}).click();
    await p.getByRole("alertdialog", {name: "Delete Server?"}).getByRole("button", {name: "Confirm"}).click();
    await expect(cell).not.toBeVisible();
}

async function deleteNetworkByName(p: Page, name: string) {
    await p.goto("/networks");
    const cell = p.getByRole("cell", {name, exact: true});
    const found = await expect(cell).toBeVisible({timeout: 5000}).then(() => true).catch(() => false);
    if (!found) return;
    await expect(cell).toHaveCount(1);
    await rowByExactName(p, name).getByRole("button", {name: "Delete"}).click();
    await p.getByRole("dialog", {name: "Delete Network"}).getByRole("button", {name: "Delete"}).click();
    await expect(cell).not.toBeVisible();
}

test.beforeAll(async ({browser}: {browser: Browser}) => {
    context = await browser.newContext();
    page = await context.newPage();
    await login(page);

    await page.goto("/networks");
    await page.getByRole("button", {name: "New Network"}).click();
    await page.getByRole("dialog", {name: "New Network"}).getByLabel("Name").fill(NETWORK_NAME);
    await page.getByRole("button", {name: "Create"}).click();
    await expect(page.getByRole("cell", {name: NETWORK_NAME, exact: true})).toBeVisible();
});

test.afterAll(async ({}, testInfo) => {
    testInfo.setTimeout(120_000);
    for (const name of [FABRIC_NAME, PAPER_NAME, VELOCITY_NAME]) {
        await deleteServerByName(page, name);
    }
    await deleteNetworkByName(page, NETWORK_NAME);
    await context.close();
});

test("fabric server can be created on a network", async () => {
    await createServer(page, {name: FABRIC_NAME, type: "FABRIC", network: NETWORK_NAME});

    // Detail page breadcrumb reflects the network it was assigned to.
    await expect(page.getByRole("link", {name: NETWORK_NAME})).toBeVisible();

    // List page: row shows the right type, and the network filter still finds it.
    await page.goto("/servers");
    const row = rowByExactName(page, FABRIC_NAME);
    await expect(row.getByRole("cell", {name: "FABRIC", exact: true})).toBeVisible();
    await page.getByRole("combobox").filter({hasText: "All Networks"}).selectOption(NETWORK_NAME);
    await expect(row).toBeVisible();
});

test("server can be started, console works, and it can be stopped again", async () => {
    await page.goto("/servers");
    await rowByExactName(page, FABRIC_NAME).click();
    await expect(page).toHaveURL(/\/servers\/[0-9a-f-]+$/);

    await page.getByRole("button", {name: "Start"}).click();
    // "Stop" only replaces "Start" once the server is Healthy — a more specific
    // gate than the "Healthy" status text, which appears twice on this page.
    await expect(page.getByRole("button", {name: "Stop"})).toBeVisible({timeout: 120_000});

    await page.getByRole("button", {name: "Console"}).click();
    // xterm.js keeps its real input as an off-screen focus proxy (not
    // Playwright-"visible") — click the terminal to focus it, then type via
    // the keyboard like a real user rather than filling the hidden textarea.
    await page.locator(".xterm").click();
    await page.keyboard.type("list");
    await page.keyboard.press("Enter");
    // Command round-trips over gRPC to the agent and back before it's echoed —
    // default 5s assertion timeout is sometimes too tight for that.
    await expect(page.getByText("list", {exact: false})).toBeVisible({timeout: 15_000});

    // Stop before the afterAll cleanup runs — Delete is only available on a stopped server.
    await page.getByRole("button", {name: "Stop"}).click();
    await expect(page.getByRole("button", {name: "Start"})).toBeVisible({timeout: 60_000});
});

test("backup can be triggered and appears in the list", async () => {
    await page.goto("/servers");
    await rowByExactName(page, FABRIC_NAME).click();
    await page.getByRole("button", {name: "Backups"}).click();

    await page.getByRole("button", {name: "Trigger Backup"}).click();
    await expect(page.getByText("0 backups")).not.toBeVisible({timeout: 60_000});
});

test("file can be created and deleted", async () => {
    // File operations go through the agent's live connection to the container,
    // so the server must be running — the previous test stopped it again.
    await page.goto("/servers");
    await rowByExactName(page, FABRIC_NAME).click();
    await expect(page).toHaveURL(/\/servers\/[0-9a-f-]+$/);
    await page.getByRole("button", {name: "Start"}).click();
    await expect(page.getByRole("button", {name: "Stop"})).toBeVisible({timeout: 120_000});

    await page.getByRole("button", {name: "Files"}).click();

    const folderName = `smoke-test-dir-${RUN_ID}`;
    await page.getByTitle("New folder").click();
    await page.getByLabel("Path (relative to /)").fill(folderName);
    await page.getByRole("button", {name: "Create"}).click();
    await expect(page.getByText(folderName, {exact: true})).toBeVisible();

    // Scope to the row itself — every file/folder row has its own hover-revealed
    // Delete button with the same title, so an unscoped getByTitle is ambiguous.
    // The name span's immediate parent is the flex row holding both the name and
    // the action buttons.
    const folderRow = page.getByText(folderName, {exact: true}).locator("..");
    await folderRow.hover();
    await folderRow.getByTitle("Delete").click();
    await page.getByRole("alertdialog", {name: "Delete File?"}).getByRole("button", {name: "Confirm"}).click();
    await expect(page.getByText(folderName, {exact: true})).not.toBeVisible();

    // Stop before the afterAll cleanup runs — Delete is only available on a stopped server.
    await page.getByRole("button", {name: "Stop"}).click();
    await expect(page.getByRole("button", {name: "Start"})).toBeVisible({timeout: 60_000});
});

test("paper server can be created", async () => {
    await createServer(page, {name: PAPER_NAME, type: "PAPER"});

    await page.goto("/servers");
    await expect(rowByExactName(page, PAPER_NAME).getByRole("cell", {name: "PAPER", exact: true})).toBeVisible();
});

test("plugin can be found via Modrinth search on the paper server", async () => {
    await page.goto("/servers");
    await rowByExactName(page, PAPER_NAME).click();
    await page.getByRole("button", {name: "Plugins"}).click();

    await page.getByRole("button", {name: "Add Plugin"}).click();
    await page.getByRole("textbox", {name: "Search Modrinth…"}).fill("EssentialsX");
    await page.getByRole("button", {name: "Search"}).click();
    await expect(page.getByRole("button", {name: "Add"}).first()).toBeVisible({timeout: 15_000});
});

test("velocity proxy can be created", async () => {
    await createServer(page, {name: VELOCITY_NAME, type: "VELOCITY"});

    await page.goto("/servers");
    await expect(rowByExactName(page, VELOCITY_NAME).getByRole("cell", {name: "VELOCITY", exact: true})).toBeVisible();
});
