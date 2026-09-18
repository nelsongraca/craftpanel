import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

test("lists backups with status badges and schedule", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();

    await expect(page.getByText("Backup Schedule", {exact: true})).toBeVisible();
    await expect(page.getByText("0 2 * * *", {exact: true})).toBeVisible();
    await expect(page.getByText("COMPLETED", {exact: true})).toBeVisible();
    await expect(page.getByText("FAILED", {exact: true})).toBeVisible();
    await expect(page.getByText("Disk full", {exact: true})).toBeVisible();
    await expect(page.getByText("(scheduled)")).toBeVisible();
});

test("triggers a backup and reloads the list", async ({page, network}) => {
    let triggered = false;
    const completed = {
        id: "backup-1",
        server_id: "srv-1",
        node_id: "node-1",
        trigger: "MANUAL",
        status: "COMPLETED",
        file_path: "/backups/backup-1.tar.gz",
        size_bytes: 1048576,
        error_message: null,
        created_at: "2025-06-20T10:00:00Z",
        completed_at: "2025-06-20T10:05:00Z",
    };
    network.use(
        http.get("/api/servers/srv-1/backups", () =>
            HttpResponse.json({
                backups: triggered
                    ? [completed, {...completed, id: "backup-2", created_at: "2025-06-22T10:00:00Z"}]
                    : [completed],
            })
        ),
        http.post("/api/servers/srv-1/backups", () => {
            triggered = true;
            return HttpResponse.json({...completed, id: "backup-2"}, {status: 202});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();
    await expect(page.getByText(/^1 backup/)).toBeVisible();

    await page.getByRole("button", {name: "Trigger Backup"}).click();
    await expect(page.getByText(/^2 backups/)).toBeVisible();
});

test("edits and saves the backup schedule", async ({page, network}) => {
    let saved: {backup_schedule: string | null; backup_max_count: number} | null = null;
    network.use(
        http.get("/api/servers/srv-1/backup-schedule", () =>
            HttpResponse.json(
                saved ?? {backup_schedule: "0 2 * * *", backup_max_count: 10}
            )
        ),
        http.put("/api/servers/srv-1/backup-schedule", async ({request}) => {
            saved = (await request.json()) as typeof saved;
            return HttpResponse.json(saved);
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();
    await page.getByRole("button", {name: "Edit", exact: true}).click();

    await page.getByPlaceholder("0 2 * * *").fill("30 4 * * *");
    await page.getByRole("button", {name: "Save", exact: true}).click();

    await expect(page.getByText("30 4 * * *", {exact: true})).toBeVisible();
});

test("deletes a backup from the list", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/servers/srv-1/backups", () =>
            HttpResponse.json({
                backups: deleted
                    ? []
                    : [
                          {
                              id: "backup-1",
                              server_id: "srv-1",
                              node_id: "node-1",
                              trigger: "MANUAL",
                              status: "COMPLETED",
                              file_path: "/backups/backup-1.tar.gz",
                              size_bytes: 1048576,
                              error_message: null,
                              created_at: "2025-06-20T10:00:00Z",
                              completed_at: "2025-06-20T10:05:00Z",
                          },
                      ],
            })
        ),
        http.delete("/api/servers/srv-1/backups/:backupId", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();
    await expect(page.getByText("COMPLETED", {exact: true})).toBeVisible();

    await page.getByTitle("Delete backup").click();
    await expect(page.getByText("No backups yet")).toBeVisible();
});

test("downloads a completed backup", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();

    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", {name: "Download"}).click();
    await downloadPromise;
});

test("renders an in-progress backup with a progress bar", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/backups", () =>
            HttpResponse.json({
                backups: [
                    {
                        id: "backup-live",
                        server_id: "srv-1",
                        node_id: "node-1",
                        trigger: "MANUAL",
                        status: "IN_PROGRESS",
                        file_path: null,
                        size_bytes: null,
                        error_message: null,
                        created_at: "2025-06-20T10:00:00Z",
                        completed_at: null,
                    },
                ],
            })
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();

    await expect(page.getByText("Backing up…")).toBeVisible();
    await expect(page.getByText("IN_PROGRESS", {exact: true})).toBeVisible();
});

test("shows the empty state when there are no backups", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/backups", () =>
            HttpResponse.json({backups: []})
        )
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Backups"}).click();

    await expect(page.getByText("No backups yet")).toBeVisible();
});
