import {test, expect, type Page} from "@playwright/test";
import {login} from "./helpers";

const RUN_ID = Date.now();
const NETWORK_NAME = `smoke-net-${RUN_ID}`;
const FABRIC_NAME = `smoke-fabric-${RUN_ID}`;
const PAPER_NAME = `smoke-paper-${RUN_ID}`;
const VELOCITY_NAME = `smoke-velocity-${RUN_ID}`;

async function createServer(
    page: Page,
    {name, type, network}: {name: string; type: string; network?: string},
) {
    await page.goto("/servers/new");
    await page.getByRole("textbox", {name: "Lowercase letters, numbers"}).fill(name);
    // Form labels aren't wired via htmlFor/id, so selects are addressed positionally:
    // Server Type is always first; Network is always last (Minecraft Version is
    // omitted for proxy types, which shifts nothing after Server Type).
    await page.getByRole("combobox").first().selectOption(type);
    if (network) {
        await page.getByRole("combobox").last().selectOption(network);
    }
    await page.getByRole("button", {name: "Create Server"}).click();
    await expect(page).toHaveURL(/\/servers\/[0-9a-f-]+$/);
}

test.beforeAll(async ({browser}) => {
    const page = await browser.newPage();
    await login(page);
    await page.goto("/networks");
    await page.getByRole("button", {name: "New Network"}).click();
    await page.getByRole("dialog", {name: "New Network"}).getByRole("textbox").first().fill(NETWORK_NAME);
    await page.getByRole("button", {name: "Create"}).click();
    await expect(page.getByRole("cell", {name: NETWORK_NAME, exact: true})).toBeVisible();
    await page.close();
});

test.beforeEach(async ({page}) => {
    await login(page);
});

test("fabric server can be created on a network", async ({page}) => {
    await createServer(page, {name: FABRIC_NAME, type: "FABRIC", network: NETWORK_NAME});
});

test("paper server can be created", async ({page}) => {
    await createServer(page, {name: PAPER_NAME, type: "PAPER"});
});

test("velocity proxy can be created", async ({page}) => {
    await createServer(page, {name: VELOCITY_NAME, type: "VELOCITY"});
});

test.afterAll(async ({browser}) => {
    const page = await browser.newPage();
    await login(page);
    await page.goto("/servers");
    for (const name of [FABRIC_NAME, PAPER_NAME, VELOCITY_NAME]) {
        const row = page.getByRole("row", {name});
        if (await row.isVisible().catch(() => false)) {
            await row.getByRole("button", {name: "Delete"}).click();
            await page.getByRole("dialog").getByRole("button", {name: "Delete"}).click();
        }
    }

    await page.goto("/networks");
    const networkRow = page.getByRole("row", {name: NETWORK_NAME});
    if (await networkRow.isVisible().catch(() => false)) {
        await networkRow.getByRole("button", {name: "Delete"}).click();
        await page.getByRole("dialog", {name: "Delete Network"}).getByRole("button", {name: "Delete"}).click();
    }
    await page.close();
});
