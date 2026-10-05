import {defineConfig, devices} from "@playwright/test";

export default defineConfig({
    testDir: "./tests/e2e/specs",
    // The mobile screenshot sweep (28 shots × 3 viewports) is a manual usability tool, not a
    // test gate — it roughly triples suite runtime. Run it explicitly via
    // `pnpm run test:e2e:mobile` (playwright.mobile.config.ts).
    testIgnore: ["**/mobile-screenshots.spec.ts"],
    outputDir: "build/test-results/playwright",
    globalSetup: require.resolve("./tests/e2e/global-setup"),
    globalTeardown: require.resolve("./tests/e2e/global-teardown"),
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    // The suite parallelises well (the dev server is not the bottleneck); a handful of workers
    // keeps the single CI job to a couple of minutes without sharding.
    workers: process.env.CI ? 3 : undefined,
    reporter: [
        ["html", {outputFolder: "build/reports/playwright"}],
        ["junit", {outputFile: "build/reports/junit/playwright.xml"}],
    ],
    use: {
        baseURL: "http://localhost:3000",
        trace: "on-first-retry",
    },
    projects: [
        {
            name: "chromium",
            use: {...devices["Desktop Chrome"]},
        },
    ],
    webServer: {
        command: ".node/bin/pnpm dev",
        url: "http://localhost:3000",
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
    },
});
