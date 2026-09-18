import {expect, test} from "../fixture";

test("renders dashboard stat cards and panels", async ({page}) => {
    await page.goto("/");

    await expect(page.getByRole("heading", {name: "Dashboard"})).toBeVisible();
    await expect(page.getByText("Node Health", {exact: true})).toBeVisible();
    await expect(page.getByText("Recent Server Activity", {exact: true})).toBeVisible();
    await expect(page.getByRole("link", {name: /^Servers /})).toBeVisible();
    await expect(page.getByRole("link", {name: /^Nodes /})).toBeVisible();
});

test("stat card navigates to the servers list", async ({page}) => {
    await page.goto("/");
    await page.getByRole("link", {name: /^Servers /}).click();

    await expect(page).toHaveURL("/servers");
});

test("node row navigates to the node detail", async ({page}) => {
    await page.goto("/");
    await page.getByRole("cell", {name: "Primary Node"}).click();

    await expect(page).toHaveURL("/nodes/node-1");
});

test("sidebar navigates between sections", async ({page}) => {
    await page.goto("/");
    await page.getByRole("link", {name: "Networks"}).first().click();

    await expect(page).toHaveURL("/networks");
    await expect(page.getByRole("heading", {name: "Networks"})).toBeVisible();
});

test("user menu signs out", async ({page}) => {
    await page.goto("/");
    await page.getByRole("button", {name: /admin/}).click();
    await page.getByRole("menuitem", {name: "Sign out"}).click();

    await expect(page).toHaveURL("/login");
});
