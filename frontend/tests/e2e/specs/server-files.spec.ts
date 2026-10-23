import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";
import {selectServerTab} from "../server-tabs";

// NOTE: the text-file CodeMirror editor renders in these specs (the old Turbopack
// "multiple instances of @codemirror/state" crash is fixed). See the scrollability
// regression test below.

const rootEntries = [
    {
        name: "server.properties",
        is_directory: false,
        size_bytes: 42,
        modified_at: "2025-06-20T10:00:00Z",
        permissions: "rw-r--r--",
    },
    {
        name: "eula.txt",
        is_directory: false,
        size_bytes: 11,
        modified_at: "2025-06-20T10:00:00Z",
        permissions: "rw-r--r--",
    },
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
                    ? [
                          ...rootEntries,
                          {
                              name: "config",
                              is_directory: true,
                              size_bytes: 0,
                              modified_at: "2025-06-20T10:00:00Z",
                              permissions: "rwxr-xr-x",
                          },
                      ]
                    : rootEntries,
            });
        }),
        http.post("/api/servers/srv-1/files/mkdir", () => {
            created = true;
            return new HttpResponse(null, {status: 204});
        }),
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
                entries: deleted ? rootEntries.filter((e) => e.name !== "eula.txt") : rootEntries,
            });
        }),
        http.delete("/api/servers/srv-1/files", () => {
            deleted = true;
            return new HttpResponse(null, {status: 204});
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    const row = page.getByText("eula.txt").locator("..");
    await row.hover();
    await row.getByTitle("Delete").click();

    await expect(page.getByText("Delete File?")).toBeVisible();
    await page.getByRole("button", {name: "Confirm"}).click();
    // Wait for the confirm dialog to close before asserting the row is gone — it stays open while
    // the delete is pending and its description also contains the filename.
    await expect(page.getByRole("alertdialog")).toBeHidden();
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
                    ? [
                          {
                              name: "eula-renamed.txt",
                              is_directory: false,
                              size_bytes: 11,
                              modified_at: "2025-06-20T10:00:00Z",
                              permissions: "rw-r--r--",
                          },
                      ]
                    : [rootEntries[1]],
            });
        }),
        http.post("/api/servers/srv-1/files/move", () => {
            renamed = true;
            return new HttpResponse(null, {status: 204});
        }),
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

test("moves a file by dragging it onto a folder row", async ({page, network}) => {
    const moves: unknown[] = [];
    network.use(
        http.get("/api/servers/srv-1/files", ({request}) => {
            const path = new URL(request.url).searchParams.get("path") ?? "/";
            if (path !== "/") return HttpResponse.json({entries: []});
            return HttpResponse.json({entries: rootEntries});
        }),
        http.post("/api/servers/srv-1/files/move", async ({request}) => {
            moves.push(await request.json());
            return new HttpResponse(null, {status: 204});
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    await page
        .getByText("eula.txt")
        .locator("..")
        .dragTo(page.getByText("world", {exact: true}).locator(".."));

    await expect.poll(() => moves).toContainEqual({source_path: "/eula.txt", destination_path: "/world/eula.txt"});
});

test("binary file shows download-only view", async ({page}) => {
    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();
    await page.getByText("world", {exact: true}).click();
    await page.getByText("level.dat").click();

    await expect(page.getByText("Binary file - use the download button to retrieve it.")).toBeVisible();
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

test("upload here targets the selected folder", async ({page, network}) => {
    let uploadedPath: string | null = null;
    network.use(
        http.get("/api/servers/srv-1/files", ({request}) => {
            const path = new URL(request.url).searchParams.get("path") ?? "/";
            if (path !== "/") return HttpResponse.json({entries: []});
            return HttpResponse.json({entries: rootEntries});
        }),
        http.post("/api/servers/srv-1/files/upload-batch", async ({request}) => {
            const directory = new URL(request.url).searchParams.get("path") ?? "/";
            const form = await request.formData();
            const file = form.get("files") as File;
            uploadedPath = `${directory === "/" ? "" : directory}/${file.name}`;
            return HttpResponse.json(
                {uploaded: 1, failed: 0, files: [{path: uploadedPath, success: true, size_bytes: 1}]},
                {status: 201},
            );
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    const row = page.getByText("world", {exact: true}).locator("..");
    await row.hover();

    const chooser = page.waitForEvent("filechooser");
    await row.getByTitle("Upload here").click();
    await (
        await chooser
    ).setFiles({
        name: "level.dat",
        mimeType: "application/octet-stream",
        buffer: Buffer.from("data"),
    });

    await expect.poll(() => uploadedPath).toBe("/world/level.dat");
    await expect(page.getByTestId("upload-dialog")).toContainText("1 uploaded · 0 failed");
});

test("uploads multiple files from the header in one batch", async ({page, network}) => {
    let uploadedNames: string[] = [];
    network.use(
        http.get("/api/servers/srv-1/files", ({request}) => {
            const path = new URL(request.url).searchParams.get("path") ?? "/";
            if (path !== "/") return HttpResponse.json({entries: []});
            return HttpResponse.json({entries: rootEntries});
        }),
        http.post("/api/servers/srv-1/files/upload-batch", async ({request}) => {
            const form = await request.formData();
            uploadedNames = form.getAll("files").map((entry) => (entry as File).name);
            return HttpResponse.json(
                {
                    uploaded: uploadedNames.length,
                    failed: 0,
                    files: uploadedNames.map((name) => ({path: `/${name}`, success: true, size_bytes: 1})),
                },
                {status: 201},
            );
        }),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();

    const chooser = page.waitForEvent("filechooser");
    await page.getByTitle("Upload files").click();
    await (
        await chooser
    ).setFiles([
        {name: "one.txt", mimeType: "text/plain", buffer: Buffer.from("1")},
        {name: "two.txt", mimeType: "text/plain", buffer: Buffer.from("2")},
    ]);

    // Multi-select always asks first.
    await page.getByRole("button", {name: "Upload", exact: true}).click();
    await expect.poll(() => uploadedNames).toEqual(["one.txt", "two.txt"]);
});

test("long text file is scrollable in the editor", async ({page, network}) => {
    const longContent = Array.from({length: 400}, (_, i) => `line ${i + 1}`).join("\n");
    network.use(
        http.get("/api/servers/srv-1/files/content", () =>
            HttpResponse.json({encoding: "utf-8", content: longContent}),
        ),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();
    await page.getByText("server.properties").click();

    const scroller = page.locator(".cm-scroller");
    await expect(scroller).toBeVisible();

    // The editor must not clip its content: the scroller needs a real height and an overflowing
    // document so the user can reach every line.
    const {scrollHeight, clientHeight, overflowY} = await scroller.evaluate((el) => ({
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
        overflowY: getComputedStyle(el).overflowY,
    }));

    expect(clientHeight).toBeGreaterThan(0);
    expect(scrollHeight).toBeGreaterThan(clientHeight);
    expect(overflowY).toBe("auto");
});

test("opens the find panel on Ctrl+F", async ({page, network}) => {
    network.use(
        http.get("/api/servers/srv-1/files/content", () =>
            HttpResponse.json({encoding: "utf-8", content: "motd=hello\nmotd=world\n"}),
        ),
    );

    await page.goto("/servers/srv-1");
    await page.getByRole("tab", {name: "Files"}).click();
    await page.getByText("server.properties").click();

    const content = page.locator(".cm-content");
    await expect(content).toBeVisible();
    await content.click();
    await page.keyboard.press("ControlOrMeta+f");

    // Regression: a second copy of @codemirror/view makes the search keybinding run (and swallow
    // the browser shortcut) while the editor never mounts the panel it registered.
    const panel = page.locator(".cm-search");
    await expect(panel).toBeVisible();
    await expect(panel.locator('input[name="search"]')).toBeVisible();

    await page.keyboard.type("motd");
    await expect(page.locator(".cm-searchMatch")).not.toHaveCount(0);

    // The panel survives an editor reconfigure (the wrap toggle rebuilds the extension set).
    await page.getByTitle(/word wrap/).click();
    await expect(panel).toBeVisible();
});

test.describe("mobile master/detail (375px)", () => {
    test.use({viewport: {width: 375, height: 800}});

    test("file tree is full-screen until a file is picked, then the editor + back button", async ({page, network}) => {
        network.use(
            http.get("/api/servers/srv-1/files/content", () =>
                HttpResponse.json({encoding: "utf-8", content: "motd=hello\n"}),
            ),
        );

        await page.goto("/servers/srv-1");
        await selectServerTab(page, "Files");

        // No file selected: the tree owns the screen, the editor pane is hidden.
        await expect(page.getByText("server.properties")).toBeVisible();
        await expect(page.locator(".cm-scroller")).not.toBeVisible();

        // Pick a file: the editor takes over and the tree is hidden.
        await page.getByText("server.properties").click();
        await expect(page.locator(".cm-scroller")).toBeVisible();
        await expect(page.getByText("eula.txt")).not.toBeVisible();

        // Back returns to the full-screen tree.
        await page.getByTitle("Back to files").click();
        await expect(page.getByText("eula.txt")).toBeVisible();
        await expect(page.locator(".cm-scroller")).not.toBeVisible();
    });

    test("row actions are reachable through the overflow menu without hover", async ({page, network}) => {
        let deleted = false;
        network.use(
            http.get("/api/servers/srv-1/files", ({request}) => {
                const path = new URL(request.url).searchParams.get("path") ?? "/";
                if (path !== "/") return HttpResponse.json({entries: []});
                return HttpResponse.json({
                    entries: deleted ? rootEntries.filter((e) => e.name !== "eula.txt") : rootEntries,
                });
            }),
            http.delete("/api/servers/srv-1/files", () => {
                deleted = true;
                return new HttpResponse(null, {status: 204});
            }),
        );

        await page.goto("/servers/srv-1");
        await selectServerTab(page, "Files");

        // No hover — tap the row's ⋯ trigger and delete from the menu.
        const row = page.getByText("eula.txt").locator("..");
        await row.getByTitle("Actions").click();
        await page.getByRole("menuitem", {name: "Delete"}).click();

        await expect(page.getByText("Delete File?")).toBeVisible();
        await page.getByRole("button", {name: "Confirm"}).click();
        await expect(page.getByRole("alertdialog")).toBeHidden();
        await expect(page.getByText("eula.txt")).not.toBeVisible();
    });

    test("the editor's find button opens the search panel without a keyboard", async ({page, network}) => {
        network.use(
            http.get("/api/servers/srv-1/files/content", () =>
                HttpResponse.json({encoding: "utf-8", content: "motd=hello\n"}),
            ),
        );

        await page.goto("/servers/srv-1");
        await selectServerTab(page, "Files");
        await page.getByText("server.properties").click();
        await expect(page.locator(".cm-scroller")).toBeVisible();

        // Ctrl+F does not exist on a touch device — the toolbar button is the only way in.
        await page.getByRole("button", {name: "Find in file"}).click();
        await expect(page.locator(".cm-search")).toBeVisible();
        await expect(page.locator(".cm-search input[name=search]")).toBeFocused();
    });
});
