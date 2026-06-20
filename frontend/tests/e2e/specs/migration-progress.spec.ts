import {expect, test} from "../fixture";

// WS mocking uses page.routeWebSocket() directly — NOT MSW ws() handlers.
// See server-console.spec.ts for rationale.

test("migration tab shows no-migrations message initially", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Migration"}).click();
    await expect(page.getByText("No migrations yet.")).toBeVisible();
});

test("Migrate button opens modal with target node", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Migration"}).click();

    await page.getByRole("button", {name: /Migrate/i}).click();

    // Modal is open and Start Migration button is visible
    await expect(
        page.getByRole("button", {name: "Start Migration"})
    ).toBeVisible();
    // node-2 appears as an option in the target node select (value attr)
    await expect(
        page.locator("option[value='node-2']")
    ).toHaveCount(1);
});

test("start migration triggers WS events and updates step tracker", async ({
                                                                               page,
                                                                           }) => {
    await page.routeWebSocket(/\/api\/migrations\/.*\/events/, (ws) => {
        ws.send(JSON.stringify({type: "status", status: "SYNCING"}));
        setTimeout(() => {
            ws.send(
                JSON.stringify({
                    type: "step.started",
                    step: 1,
                    description: "Syncing world data",
                })
            );
        }, 80);
        setTimeout(() => {
            ws.send(
                JSON.stringify({
                    type: "rsync.progress",
                    step: 1,
                    percent: 45,
                })
            );
        }, 160);
    });

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Migration"}).click();
    await page.getByRole("button", {name: /Migrate/i}).click();

    await expect(
        page.getByRole("button", {name: "Start Migration"})
    ).toBeVisible();
    await page.getByRole("button", {name: "Start Migration"}).click();

    // Status updates to SYNCING
    await expect(page.getByText("SYNCING")).toBeVisible({timeout: 5000});

    // Step row appears
    await expect(
        page.getByText("Syncing world data")
    ).toBeVisible({timeout: 3000});

    // Rsync progress bar at 45%
    await expect(
        page.locator('[style*="width: 45%"]')
    ).toBeVisible({timeout: 3000});
});

test("migration completed event updates status", async ({page}) => {
    await page.routeWebSocket(/\/api\/migrations\/.*\/events/, (ws) => {
        ws.send(JSON.stringify({type: "status", status: "SYNCING"}));
        setTimeout(() => {
            ws.send(JSON.stringify({type: "completed"}));
        }, 100);
    });

    await page.goto("/servers/srv-1");
    await page.getByRole("button", {name: "Migration"}).click();
    await page.getByRole("button", {name: /Migrate/i}).click();
    await page.getByRole("button", {name: "Start Migration"}).click();

    await expect(page.getByText("COMPLETED")).toBeVisible({timeout: 5000});
});
