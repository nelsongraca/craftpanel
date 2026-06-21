import {expect, test} from "../fixture";

// authRefresh default handler returns 200 → auto-authenticated on any page load

test.describe("mobile drawer (375px)", () => {
    test.use({viewport: {width: 375, height: 800}});

    // The drawer slides off-canvas via transform, so the sidebar stays in the DOM
    // and Playwright still reports it "visible" — assert its on/off-screen X instead.
    const isOffCanvas = async (box: {x: number} | null) => box !== null && box.x < 0;

    test("sidebar off-canvas by default, opens via hamburger, closes on navigation", async ({page}) => {
        await page.goto("/servers");
        const aside = page.locator("aside");

        // Closed: translated off the left edge.
        expect(await isOffCanvas(await aside.boundingBox())).toBe(true);

        // Open the drawer → slides on-screen (x >= 0).
        await page.getByRole("button", {name: "Open navigation"}).click();
        await expect(page.getByRole("link", {name: "All Servers"})).toBeInViewport();
        await expect(page.getByRole("link", {name: "Networks"})).toBeInViewport();

        // Navigate → drawer must close again.
        await page.getByRole("link", {name: "Networks"}).click();
        await expect(page).toHaveURL(/\/networks/);
        await expect.poll(async () => isOffCanvas(await aside.boundingBox())).toBe(true);
    });
});

test.describe("desktop shell (1280px)", () => {
    test.use({viewport: {width: 1280, height: 800}});

    test("sidebar visible, no hamburger", async ({page}) => {
        await page.goto("/servers");
        await expect(page.getByRole("link", {name: "All Servers"})).toBeVisible();
        await expect(page.getByRole("button", {name: "Open navigation"})).not.toBeVisible();
    });
});
