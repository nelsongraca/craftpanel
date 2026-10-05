import {defineConfig, devices} from "@playwright/test";

// Manual mobile usability screenshot sweep — NOT part of the default suite (see testIgnore in
// playwright.config.ts) or CI. Run it explicitly, then review the PNGs under
// build/reports/mobile/<viewport>/:
//
//   .node/bin/pnpm run test:e2e:mobile
export default defineConfig({
    testDir: "./tests/e2e/specs",
    testMatch: "**/mobile-screenshots.spec.ts",
    outputDir: "build/test-results/playwright-mobile",
    fullyParallel: true,
    workers: 1,
    reporter: [["list"]],
    use: {
        baseURL: "http://localhost:3000",
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
