import {http, HttpResponse} from "msw";
import {expect, test} from "../fixture";

// Desktop table + mobile cards render the same text → scope to the table.
const table = (page: import("@playwright/test").Page) => page.locator("table");

test("lists users with status", async ({page}) => {
    await page.goto("/users");

    await expect(page.getByRole("heading", {name: "Users", level: 1})).toBeVisible();
    await expect(table(page).getByText("admin", {exact: true})).toBeVisible();
    await expect(table(page).getByText("viewer", {exact: true})).toBeVisible();
    await expect(table(page).getByText("Inactive", {exact: true})).toBeVisible();
});

test("creates a user through the dialog", async ({page, network}) => {
    let created = false;
    network.use(
        http.post("/api/users", () => {
            created = true;
            return HttpResponse.json(
                {
                    id: "user-new",
                    username: "newbie",
                    email: "newbie@craftpanel.test",
                    is_active: true,
                    created_at: "2025-01-03T00:00:00Z",
                    must_change_password: false,
                    groups: [],
                    last_login_at: null,
                },
                {status: 201}
            );
        }),
        http.get("/api/users", () =>
            HttpResponse.json({
                users: created
                    ? [
                          {id: "user-new", username: "newbie", email: "newbie@craftpanel.test", is_active: true, created_at: "2025-01-03T00:00:00Z", groups: [], last_login_at: null},
                      ]
                    : [],
            })
        )
    );

    await page.goto("/users");
    await page.getByRole("button", {name: "New User"}).click();
    await expect(page.getByRole("dialog", {name: "Create User"})).toBeVisible();

    await page.getByRole("textbox").nth(0).fill("newbie");
    await page.getByRole("textbox").nth(1).fill("newbie@craftpanel.test");
    await page.getByRole("textbox").nth(2).fill("secret123");
    await page.getByRole("button", {name: "Create"}).click();

    await expect(page.getByRole("dialog", {name: "Create User"})).not.toBeVisible();
    await expect(table(page).getByText("newbie", {exact: true})).toBeVisible();
});

test("edits a user and toggles active", async ({page}) => {
    await page.goto("/users");
    await page.getByRole("button", {name: "Edit"}).first().click();
    await expect(page.getByRole("dialog", {name: "Edit User"})).toBeVisible();

    await page.getByRole("checkbox", {name: "User is active"}).click();
    await page.getByRole("button", {name: "Save", exact: true}).click();
    await expect(page.getByRole("dialog", {name: "Edit User"})).not.toBeVisible();
});

test("resets a user password", async ({page, network}) => {
    network.use(
        http.put("/api/users/:id/password", () =>
            new HttpResponse(null, {status: 204})
        )
    );

    await page.goto("/users");
    // Reset password is hidden for self, so only the viewer row has it.
    await page.getByRole("button", {name: "Reset password"}).first().click();
    await expect(page.getByRole("dialog", {name: /Reset Password/})).toBeVisible();

    await page.getByRole("textbox").nth(0).fill("newpass123");
    await page.getByRole("textbox").nth(1).fill("newpass123");
    await page.getByRole("button", {name: "Reset Password", exact: true}).click();

    await expect(page.getByRole("dialog", {name: /Reset Password/})).not.toBeVisible();
});

test("shows mismatched password error", async ({page}) => {
    await page.goto("/users");
    await page.getByRole("button", {name: "Reset password"}).first().click();

    await page.getByRole("textbox").nth(0).fill("newpass123");
    await page.getByRole("textbox").nth(1).fill("different");
    await page.getByRole("button", {name: "Reset Password", exact: true}).click();

    await expect(page.getByText("Passwords do not match")).toBeVisible();
});

test("deletes a user after confirming", async ({page}) => {
    await page.goto("/users");
    await page.getByRole("button", {name: "Delete"}).first().click();

    const dialog = page.getByRole("dialog", {name: "Delete User"});
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", {name: "Delete"}).click();
    await expect(dialog).not.toBeVisible();
});

test("manages group assignments", async ({page, network}) => {
    network.use(
        http.get("/api/users/:userId/assignments", () =>
            HttpResponse.json({
                assignments: [
                    {id: "assign-1", group_id: "group-1", scope_type: "GLOBAL", scope_id: null},
                ],
            })
        )
    );

    await page.goto("/users");
    await page.getByRole("button", {name: "Manage groups"}).first().click();

    const dialog = page.getByRole("dialog", {name: /Groups -/});
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("Current Assignments", {exact: true})).toBeVisible();
    await expect(dialog.getByText("Super Admin", {exact: true})).toBeVisible();
});
