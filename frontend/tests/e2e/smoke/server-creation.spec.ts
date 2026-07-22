import {test, expect, type Page, type Browser, type BrowserContext} from "@playwright/test";
import {login} from "./helpers";

// Refresh tokens rotate on every use (old one revoked) — logging in fresh per test
// in the same browser context races token rotation and randomly 401s. One shared
// login for the whole file avoids that; tests still run serially against real state
// (each depends on the network/servers earlier tests created).
test.describe.configure({mode: "serial"});

const RUN_ID = Date.now();
const NETWORK_NAME = `smoke-net-${RUN_ID}`;
const FABRIC_NAME = `smoke-fabric-${RUN_ID}`;
const PAPER_NAME = `smoke-paper-${RUN_ID}`;
const VELOCITY_NAME = `smoke-velocity-${RUN_ID}`;

let context: BrowserContext;
let page: Page;

async function createServer(
    p: Page,
    {name, type, network}: {name: string; type: string; network?: string},
) {
    await p.goto("/servers/new");
    await p.getByRole("textbox", {name: "Lowercase letters, numbers"}).fill(name);
    // Form labels aren't wired via htmlFor/id, so selects are addressed positionally:
    // Server Type is always first; Network is always last (Minecraft Version is
    // omitted for proxy types, which shifts nothing after Server Type).
    await p.getByRole("combobox").first().selectOption(type);
    if (network) {
        await p.getByRole("combobox").last().selectOption(network);
    }
    await p.getByRole("button", {name: "Create Server"}).click();
    await expect(p).toHaveURL(/\/servers\/[0-9a-f-]+$/);
}

async function deleteServerByName(p: Page, name: string) {
    await p.goto("/servers");
    const row = p.getByRole("row", {name});
    await expect(row).toBeVisible();
    await row.getByRole("button", {name: "Delete"}).click();
    await p.getByRole("alertdialog", {name: "Delete Server?"}).getByRole("button", {name: "Confirm"}).click();
    await expect(p.getByRole("row", {name})).not.toBeVisible();
}

async function deleteNetworkByName(p: Page, name: string) {
    await p.goto("/networks");
    const row = p.getByRole("row", {name});
    await expect(row).toBeVisible();
    await row.getByRole("button", {name: "Delete"}).click();
    await p.getByRole("dialog", {name: "Delete Network"}).getByRole("button", {name: "Delete"}).click();
    await expect(p.getByRole("row", {name})).not.toBeVisible();
}

test.beforeAll(async ({browser}: {browser: Browser}) => {
    context = await browser.newContext();
    page = await context.newPage();
    await login(page);

    await page.goto("/networks");
    await page.getByRole("button", {name: "New Network"}).click();
    await page.getByRole("dialog", {name: "New Network"}).getByRole("textbox").first().fill(NETWORK_NAME);
    await page.getByRole("button", {name: "Create"}).click();
    await expect(page.getByRole("cell", {name: NETWORK_NAME, exact: true})).toBeVisible();
});

test.afterAll(async () => {
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
    const row = page.getByRole("row", {name: FABRIC_NAME});
    await expect(row.getByRole("cell", {name: "FABRIC", exact: true})).toBeVisible();
    await page.getByRole("combobox").filter({hasText: "All Networks"}).selectOption(NETWORK_NAME);
    await expect(row).toBeVisible();
});

test("paper server can be created", async () => {
    await createServer(page, {name: PAPER_NAME, type: "PAPER"});

    await page.goto("/servers");
    await expect(page.getByRole("row", {name: PAPER_NAME}).getByRole("cell", {name: "PAPER", exact: true})).toBeVisible();
});

test("velocity proxy can be created", async () => {
    await createServer(page, {name: VELOCITY_NAME, type: "VELOCITY"});

    await page.goto("/servers");
    await expect(page.getByRole("row", {name: VELOCITY_NAME}).getByRole("cell", {name: "VELOCITY", exact: true})).toBeVisible();
});
