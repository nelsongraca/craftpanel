import {mkdirSync} from "node:fs";
import path from "node:path";
import {http, HttpResponse} from "msw";
import type {Page} from "@playwright/test";
import {expect, test} from "../fixture";

// One-off mobile usability screenshot sweep. Excluded from the default suite and CI via
// testIgnore in playwright.config.ts — run explicitly, then review the PNGs under
// build/reports/mobile/.
//
//   .node/bin/pnpm run test:e2e:mobile
//
// Every route (and key dialog state) is captured at three portrait viewports. For each
// screenshot the runner prints the page's scrollWidth vs clientWidth so horizontal
// overflow is visible without writing assertions.

const OUT_ROOT = path.resolve(__dirname, "../../../build/reports/mobile");

const VIEWPORTS = [
    {name: "360x800-s21fe", width: 360, height: 800},
    {name: "375x812-iphone-se", width: 375, height: 812},
    {name: "390x844-iphone-14", width: 390, height: 844},
] as const;

// Fixture IDs from tests/e2e/msw/fixtures/data.ts
const SERVER_ID = "srv-1"; // PAPER, HEALTHY
const STOPPED_SERVER_ID = "srv-2"; // PAPER, STOPPED → exposes Delete
const PROXY_ID = "srv-proxy"; // VELOCITY → no Mods/Migration tabs
const NODE_ID = "node-1";

// Tab labels the UI actually renders for srv-1 (PAPER): the "Mods" tab is
// relabelled "Plugins" for non-mod-loader server types.
const SERVER_TABS = [
    "Overview",
    "Metrics",
    "Console",
    "Files",
    "Plugins",
    "Backups",
    "Configuration",
    "Ports",
    "Migration",
] as const;

interface Shot {
    /** Slug used in the output filename. */
    name: string;
    /** Absolute URL path to visit. */
    route: string;
    /** Optional interaction performed once the page has settled, before capture. */
    act?: (page: Page) => Promise<void>;
    /** Force an unauthenticated session (authRefresh → 401) before navigating. */
    unauthenticated?: boolean;
}

const ROUTES: Shot[] = [
    {name: "login", route: "/login", unauthenticated: true},
    {name: "dashboard", route: "/"},
    {name: "servers", route: "/servers"},
    {name: "servers-new", route: "/servers/new"},
    {name: "networks", route: "/networks"},
    {name: "nodes", route: "/nodes"},
    {name: "node-detail", route: `/nodes/${NODE_ID}`},
    {name: "alerts", route: "/alerts"},
    {name: "users", route: "/users"},
    {name: "groups", route: "/groups"},
    {name: "settings", route: "/settings"},
    {name: "account", route: "/account"},
    {name: "force-password-change", route: "/force-password-change"},
];

// Every server-detail tab is a distinct first-paint of the detail shell.
for (const tab of SERVER_TABS) {
    ROUTES.push({
        name: `server-detail-${tab.toLowerCase()}`,
        route: `/servers/${SERVER_ID}`,
        act: async (page) => {
            await page.getByRole("tab", {name: tab}).click();
            // Lazy tab chunks fetch their own data after the dynamic import resolves; wait out
            // the "Loading…" placeholder so the capture shows content, not a spinner.
            const loading = page.getByText("Loading…", {exact: true});
            if (await loading.count()) {
                await loading
                    .first()
                    .waitFor({state: "hidden", timeout: 5000})
                    .catch(() => {});
            }
        },
    });
}

ROUTES.push({name: "proxy-detail-overview", route: `/servers/${PROXY_ID}`});

// Dialog / modal states reachable from a page load.
const DIALOGS: Shot[] = [
    {
        name: "users-new-user-dialog",
        route: "/users",
        act: async (page) => {
            await page.getByRole("button", {name: "New User", exact: true}).click();
            await expect(page.getByRole("dialog")).toBeVisible();
        },
    },
    {
        name: "groups-create-dialog",
        route: "/groups",
        act: async (page) => {
            await page.getByRole("button", {name: /New Group|Create Group/i}).click();
            await expect(page.getByRole("dialog")).toBeVisible();
        },
    },
    {
        name: "networks-create-dialog",
        route: "/networks",
        act: async (page) => {
            await page.getByRole("button", {name: /New Network|Create Network/i}).click();
            await expect(page.getByRole("dialog")).toBeVisible();
        },
    },
    {
        name: "server-detail-delete-confirm",
        route: `/servers/${STOPPED_SERVER_ID}`,
        act: async (page) => {
            await page.getByRole("button", {name: "Delete", exact: true}).click();
            await expect(page.getByRole("alertdialog")).toBeVisible();
        },
    },
    {
        name: "nav-drawer-open",
        route: "/servers",
        act: async (page) => {
            await page.getByRole("button", {name: "Open navigation"}).click();
            await expect(page.getByRole("link", {name: "All Servers"})).toBeInViewport();
        },
    },
];

const ALL_SHOTS = [...ROUTES, ...DIALOGS];

// The dashboard WS carries live container RAM/CPU in its first-message snapshot; the REST Server
// DTO has no live metrics. MSW's default handlers exclude WS (they break Turbopack HMR), so without
// this the list/overview bars render the "-" degraded state. Feed a snapshot so live data shows.
const LIVE_SNAPSHOT = {
    type: "snapshot",
    payload: {
        servers: [
            {
                id: "srv-1",
                display_name: "Survival World",
                status: "HEALTHY",
                node_id: "node-1",
                metrics: {
                    cpu_percent: 12.0,
                    ram_used_mb: 1024,
                    net_in_bytes: 0,
                    net_out_bytes: 0,
                    block_in_bytes: 0,
                    block_out_bytes: 0,
                },
            },
            {id: "srv-2", display_name: "Creative World", status: "STOPPED", node_id: "node-1"},
            {
                id: "srv-proxy",
                display_name: "Velocity Proxy",
                status: "HEALTHY",
                node_id: "node-1",
                metrics: {
                    cpu_percent: 3.2,
                    ram_used_mb: 256,
                    net_in_bytes: 0,
                    net_out_bytes: 0,
                    block_in_bytes: 0,
                    block_out_bytes: 0,
                },
            },
        ],
        nodes: [
            {id: "node-1", display_name: "Primary Node", status: "ACTIVE", health: "HEALTHY"},
            {id: "node-2", display_name: "Secondary Node", status: "ACTIVE", health: "HEALTHY"},
        ],
    },
};

async function mockDashboardWs(page: Page) {
    await page.routeWebSocket(/\/api\/ws(\?|$)/, (ws) => {
        ws.send(JSON.stringify(LIVE_SNAPSHOT));
    });
}

async function measure(page: Page): Promise<string> {
    return page.evaluate(() => {
        const el = document.documentElement;
        // Document-level overflow (page itself wider than the viewport).
        const docOver = el.scrollWidth > el.clientWidth ? ` docOVERFLOW(+${el.scrollWidth - el.clientWidth}px)` : "";
        // Inner scroll containers (the app shell scrolls <main>, not <body>, so a page
        // can look fine at document level while a child overflows horizontally).
        const wide = Array.from(document.querySelectorAll<HTMLElement>("main, main *"))
            .filter((n) => n.scrollWidth > n.clientWidth + 1 && n.clientWidth > 0)
            .slice(0, 5)
            .map(
                (n) =>
                    `${n.tagName.toLowerCase()}.${(n.className || "").toString().split(" ")[0] || "-"}(+${n.scrollWidth - n.clientWidth})`,
            );
        const innerOver = wide.length ? ` innerOVERFLOW[${wide.join(", ")}]` : "";
        return `${el.clientWidth}x${el.clientHeight}${docOver}${innerOver}`;
    });
}

test.describe("mobile screenshot sweep", () => {
    for (const vp of VIEWPORTS) {
        test.describe(vp.name, () => {
            test.use({viewport: {width: vp.width, height: vp.height}});

            const dir = path.join(OUT_ROOT, vp.name);
            test.beforeAll(() => {
                mkdirSync(dir, {recursive: true});
            });

            for (const shot of ALL_SHOTS) {
                test(shot.name, async ({page, network}) => {
                    if (shot.unauthenticated) {
                        network.use(
                            http.post("/api/auth/refresh", () =>
                                HttpResponse.json({message: "No session"}, {status: 401}),
                            ),
                        );
                    } else {
                        await mockDashboardWs(page);
                    }
                    await page.goto(shot.route);
                    await page.waitForLoadState("networkidle");
                    if (shot.act) {
                        await shot.act(page);
                        // Tab switches kick off their own data fetches — let them settle so we
                        // capture loaded content, not the "Loading…" placeholder.
                        await page.waitForLoadState("networkidle");
                    }
                    // Let dialog open/close animations settle before capture.
                    await page.waitForTimeout(250);

                    const metrics = await measure(page);
                    const vpBox = `vp=${vp.width}x${vp.height}`;

                    // Any open dialog: does it fit the viewport, and can it scroll?
                    let dialogInfo = "";
                    const dialog = page.getByRole("dialog").or(page.getByRole("alertdialog"));
                    if (await dialog.count()) {
                        const box = await dialog.first().boundingBox();
                        if (box) {
                            const overflowsVp = box.y + box.height > vp.height + 1 || box.y < -1;
                            const canScroll = await dialog.first().evaluate((n) => {
                                const s = getComputedStyle(n);
                                return n.scrollHeight > n.clientHeight + 1 && s.overflowY !== "visible";
                            });
                            dialogInfo =
                                ` dialog=${Math.round(box.width)}x${Math.round(box.height)}@y${Math.round(box.y)}` +
                                (overflowsVp ? " dialogOVERFLOWS_VIEWPORT" : "") +
                                (canScroll ? " dialogScrolls" : " dialogNO_SCROLL");
                        }
                    }

                    console.log(`[mobile-sweep] ${vp.name}/${shot.name}: ${vpBox} ${metrics}${dialogInfo}`);

                    // Viewport screenshot, NOT fullPage: the app is a fixed shell that scrolls
                    // <main> internally, so fullPage stitching renders inner scrollers blank.
                    await page.screenshot({
                        path: path.join(dir, `${shot.name}.png`),
                    });
                });
            }
        });
    }
});
