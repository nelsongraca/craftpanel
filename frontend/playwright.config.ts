import {defineConfig, devices} from "@playwright/test";

export default defineConfig({
    testDir: "./tests/e2e/specs",
    outputDir: "build/test-results/playwright",
    globalSetup: require.resolve("./tests/e2e/global-setup"),
    globalTeardown: require.resolve("./tests/e2e/global-teardown"),
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    workers: process.env.CI ? 1 : undefined,
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
