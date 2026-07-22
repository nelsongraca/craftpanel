import {defineConfig, devices} from "@playwright/test";

// Smoke suite: runs against a real deployed environment (SMOKE_BASE_URL), no MSW,
// no local dev server. Triggered from a dedicated container inside the home network,
// never from CI (see docker/smoke-runner).
export default defineConfig({
    testDir: "./tests/e2e/smoke",
    outputDir: "build/test-results/playwright-smoke",
    fullyParallel: false,
    retries: 1,
    workers: 1,
    reporter: [
        ["html", {outputFolder: "build/reports/playwright-smoke"}],
        ["junit", {outputFile: "build/reports/junit/playwright-smoke.xml"}],
        ["list"],
    ],
    use: {
        baseURL: process.env.SMOKE_BASE_URL ?? "http://localhost:3000",
        trace: "retain-on-failure",
    },
    projects: [
        {
            name: "chromium",
            use: {...devices["Desktop Chrome"]},
        },
    ],
});
