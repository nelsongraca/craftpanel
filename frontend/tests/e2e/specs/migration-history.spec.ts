import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";
import {fakeMigrationWithSteps} from "../msw/fixtures/data";

// WS mocking via page.routeWebSocket() — see server-console.spec.ts rationale.

test("renders migration history and expands a completed migration", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/migrations", () =>
            HttpResponse.json({migrations: [fakeMigrationWithSteps]})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Migration"}).click();

    await expect(page.getByText("COMPLETED", {exact: true})).toBeVisible();
    // First card is open by default — steps render.
    await expect(page.getByText("Syncing world data")).toBeVisible();
    await expect(page.getByText("Cutting over DNS")).toBeVisible();
});

test("migrate modal submits and streams live progress", async ({page}) => {
    await page.routeWebSocket(/\/api\/migrations\/.*\/events/, (ws) => {
        ws.send(JSON.stringify({type: "status", status: "SYNCING"}));
        setTimeout(() => {
            ws.send(JSON.stringify({type: "step.started", step: 1, description: "Syncing world data"}));
        }, 80);
        setTimeout(() => {
            ws.send(JSON.stringify({type: "rsync.progress", step: 1, percent: 45}));
        }, 160);
        setTimeout(() => {
            ws.send(JSON.stringify({type: "failed", error: "rsync exited 23"}));
        }, 240);
    });

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Migration"}).click();
    await page.getByRole("button", {name: /Migrate/i}).click();

    await page.getByRole("button", {name: "Start Migration"}).click();

    await expect(page.getByText("Active Migration")).toBeVisible();
    await expect(page.getByText("Syncing world data")).toBeVisible({timeout: 3000});
    await expect(page.getByText("rsync exited 23")).toBeVisible({timeout: 3000});
});

test("migrate modal validates target node availability", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/migrations", () =>
            HttpResponse.json({migrations: []})
        ),
        http.get("/api/nodes", () =>
            HttpResponse.json([])
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Migration"}).click();
    await page.getByRole("button", {name: /Migrate/i}).click();

    await expect(
        page.getByText("No eligible target nodes available", {exact: false})
    ).toBeVisible();
});
