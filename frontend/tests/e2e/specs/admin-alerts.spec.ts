import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

const table = (page: import("@playwright/test").Page) => page.locator("table");

test("renders thresholds and alert events", async ({page}) => {
    await page.goto("/alerts");

    await expect(page.getByRole("heading", {name: "Alerts"})).toBeVisible();
    await expect(page.getByRole("heading", {name: "Thresholds"})).toBeVisible();
    await expect(table(page).getByText("cpu_percent", {exact: true})).toBeVisible();
    await expect(table(page).getByText("node-1 CPU above 90%")).toBeVisible();
});

test("toggles the events filter", async ({page}) => {
    await page.goto("/alerts");

    await page.getByRole("button", {name: "All Events"}).click();
    await expect(page.getByRole("button", {name: "Active Only"})).toBeVisible();
    await page.getByRole("button", {name: "Active Only"}).click();
    await expect(page.getByRole("button", {name: "All Events"})).toBeVisible();
});

test("opens the new threshold modal and validates scope", async ({page}) => {
    await page.goto("/alerts");
    await page.getByRole("button", {name: "New Threshold"}).click();

    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("New Alert Threshold")).toBeVisible();

    const create = dialog.getByRole("button", {name: "Create", exact: true});
    await expect(create).toBeDisabled();

    // Scope select is the second combobox in the modal (Scope Type is first).
    await dialog.getByRole("combobox").nth(1).click();
    await page.getByRole("option", {name: "Primary Node"}).click();
    await expect(create).toBeEnabled();
});

test("creates a threshold", async ({page, network}) => {
    let created = false;
    network.use(
        http.get("/api/alerts/thresholds", () =>
            HttpResponse.json({
                thresholds: created
                    ? [
                          {id: "threshold-new", scope_type: "NODE", scope_id: "node-1", metric: "cpu_percent", threshold_value: 80, threshold_state: null, created_at: "2025-01-03T00:00:00Z"},
                      ]
                    : [],
            })
        ),
        http.post("/api/alerts/thresholds", () => {
            created = true;
            return HttpResponse.json(
                {id: "threshold-new", scope_type: "NODE", scope_id: "node-1", metric: "cpu_percent", threshold_value: 80, threshold_state: null, created_at: "2025-01-03T00:00:00Z"},
                {status: 201}
            );
        })
    );

    await page.goto("/alerts");
    await page.getByRole("button", {name: "New Threshold"}).click();

    const dialog = page.getByRole("dialog");
    await dialog.getByRole("combobox").nth(1).click();
    await page.getByRole("option", {name: "Primary Node"}).click();
    await dialog.getByRole("button", {name: "Create", exact: true}).click();

    await expect(dialog).not.toBeVisible();
    await expect(table(page).getByText("cpu_percent", {exact: true})).toBeVisible();
});

test("deletes a threshold", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/alerts/thresholds", () =>
            HttpResponse.json({
                thresholds: deleted
                    ? []
                    : [{id: "threshold-1", scope_type: "NODE", scope_id: "node-1", metric: "cpu_percent", threshold_value: 90, threshold_state: null, created_at: "2025-01-01T00:00:00Z"}],
            })
        ),
        http.delete("/api/alerts/thresholds/:id", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/alerts");
    await page.getByTitle("Delete threshold").first().click();

    await expect(page.getByText("No thresholds configured.")).toBeVisible();
});
