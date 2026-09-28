import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

const restartJob = {
    id: "job-1",
    server_id: "srv-1",
    type: "RESTART",
    cron_expression: "0 4 * * *",
    payload: null,
    enabled: true,
    last_fired_at: "2025-06-20T04:00:00Z",
};

test("lists scheduled jobs", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Jobs"}).click();

    await expect(page.getByText("2 scheduled jobs")).toBeVisible();
    await expect(page.getByText("Restart server")).toBeVisible();
    await expect(page.getByText("Console command")).toBeVisible();
    await expect(page.getByText("say hello")).toBeVisible();
    await expect(page.getByText("(disabled)")).toBeVisible();
    await expect(page.getByText(/Last fired/)).toBeVisible();
});

test("shows the empty state when there are no jobs", async ({page, network}) => {
    network.use(http.get("/api/servers/srv-1/jobs", () => HttpResponse.json([])));

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Jobs"}).click();

    await expect(page.getByText("No scheduled jobs yet")).toBeVisible();
});

test("creates a job from the dialog", async ({page, network}) => {
    let created = false;
    network.use(
        http.get("/api/servers/srv-1/jobs", () => HttpResponse.json(created ? [restartJob] : [])),
        http.post("/api/servers/srv-1/jobs", () => {
            created = true;
            return HttpResponse.json(restartJob, {status: 201});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Jobs"}).click();
    await expect(page.getByText("No scheduled jobs yet")).toBeVisible();

    await page.getByRole("button", {name: "Add Job"}).click();
    await page.getByRole("dialog").getByRole("button", {name: "Save", exact: true}).click();

    await expect(page.getByText("Restart server")).toBeVisible();
});

test("disables a job", async ({page, network}) => {
    let disabled = false;
    network.use(
        http.get("/api/servers/srv-1/jobs", () => HttpResponse.json([{...restartJob, enabled: !disabled}])),
        http.patch("/api/servers/srv-1/jobs/:jobId", () => {
            disabled = true;
            return HttpResponse.json({...restartJob, enabled: false});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Jobs"}).click();

    await page.getByRole("button", {name: "Disable", exact: true}).click();
    await expect(page.getByText("(disabled)")).toBeVisible();
});

test("deletes a job", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/servers/srv-1/jobs", () => HttpResponse.json(deleted ? [] : [restartJob])),
        http.delete("/api/servers/srv-1/jobs/:jobId", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Jobs"}).click();
    await expect(page.getByText("Restart server")).toBeVisible();

    await page.getByTitle("Delete job").click();
    await expect(page.getByText("No scheduled jobs yet")).toBeVisible();
});
