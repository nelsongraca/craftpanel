import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

// NOTE: opening a *text* file renders CodeMirror, which currently crashes in the
// Turbopack dev server ("multiple instances of @codemirror/state"). The editor is
// covered by the vitest unit tests; these specs exercise the surrounding tree,
// prompts, binary view and downloads instead.

const rootEntries = [
    {name: "server.properties", is_directory: false, size_bytes: 42, modified_at: "2025-06-20T10:00:00Z", permissions: "rw-r--r--"},
    {name: "eula.txt", is_directory: false, size_bytes: 11, modified_at: "2025-06-20T10:00:00Z", permissions: "rw-r--r--"},
    {name: "world", is_directory: true, size_bytes: 0, modified_at: "2025-06-20T10:00:00Z", permissions: "rwxr-xr-x"},
];

test("lists root entries and the empty editor state", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    await expect(page.getByText("server.properties")).toBeVisible();
    await expect(page.getByText("eula.txt")).toBeVisible();
    await expect(page.getByText("Select a file to edit")).toBeVisible();
});

test("creates a folder via the prompt dialog", async ({page, network}) => {
    let created = false;
    network.use(
        http.get("/api/servers/srv-1/files", ({request}) => {
            const path = new URL(request.url).searchParams.get("path") ?? "/";
            if (path !== "/") return HttpResponse.json({entries: []});
            return HttpResponse.json({
                entries: created
                    ? [...rootEntries, {name: "config", is_directory: true, size_bytes: 0, modified_at: "2025-06-20T10:00:00Z", permissions: "rwxr-xr-x"}]
                    : rootEntries,
            });
        }),
        http.post("/api/servers/srv-1/files/mkdir", () => {
            created = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    await page.getByTitle("New folder").click();
    await expect(page.getByText("New Folder")).toBeVisible();
    await page.getByRole("textbox").fill("config");
    await page.getByRole("button", {name: "Create"}).click();

    await expect(page.getByText("config", {exact: true})).toBeVisible();
});

test("expands a directory to reveal children", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    await page.getByText("world", {exact: true}).click();
    await expect(page.getByText("level.dat")).toBeVisible();
});

test("deletes a file after confirming", async ({page, network}) => {
    let deleted = false;
    network.use(
        http.get("/api/servers/srv-1/files", ({request}) => {
            const path = new URL(request.url).searchParams.get("path") ?? "/";
            if (path !== "/") return HttpResponse.json({entries: []});
            return HttpResponse.json({
                entries: deleted
                    ? rootEntries.filter((e) => e.name !== "eula.txt")
                    : rootEntries,
            });
        }),
        http.delete("/api/servers/srv-1/files", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    const row = page.getByText("eula.txt").locator("..");
    await row.hover();
    await row.getByTitle("Delete").click();

    await expect(page.getByText("Delete File?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();
    await expect(page.getByText("eula.txt")).not.toBeVisible();
});

test("renames a file via the inline input", async ({page, network}) => {
    let renamed = false;
    network.use(
        http.get("/api/servers/srv-1/files", ({request}) => {
            const path = new URL(request.url).searchParams.get("path") ?? "/";
            if (path !== "/") return HttpResponse.json({entries: []});
            return HttpResponse.json({
                entries: renamed
                    ? [{name: "eula-renamed.txt", is_directory: false, size_bytes: 11, modified_at: "2025-06-20T10:00:00Z", permissions: "rw-r--r--"}]
                    : [rootEntries[1]],
            });
        }),
        http.post("/api/servers/srv-1/files/move", () => {
            renamed = true;
            return new HttpResponse(null, {status: 204});
        })
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    const row = page.getByText("eula.txt").locator("..");
    await row.hover();
    await row.getByTitle("Rename").click();

    const rename = page.locator("input.font-mono");
    await rename.fill("eula-renamed.txt");
    await rename.press("Enter");
    await expect(page.getByText("eula-renamed.txt")).toBeVisible();
});

test("binary file shows download-only view", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();
    await page.getByText("world", {exact: true}).click();
    await page.getByText("level.dat").click();

    await expect(
        page.getByText("Binary file - use the download button to retrieve it.")
    ).toBeVisible();
    await expect(page.getByRole("button", {name: "Download", exact: true}).last()).toBeVisible();
});

test("downloads a file through the tree action", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    const row = page.getByText("server.properties").locator("..");
    await row.hover();

    const downloadPromise = page.waitForEvent("download");
    await row.getByTitle("Download").click();
    await downloadPromise;
});
