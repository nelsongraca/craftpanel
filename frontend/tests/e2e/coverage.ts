import MCR from "monocart-coverage-reports";
import {coverageOptions} from "./coverage-options.mjs";

const isEnabled = !!process.env.E2E_COVERAGE;

let mcr: ReturnType<typeof MCR> | null = null;

function getMCR() {
    if (!mcr) {
        mcr = MCR(coverageOptions);
    }
    return mcr;
}

export async function cleanCache() {
    if (!isEnabled) return;
    getMCR().cleanCache();
}

export async function generateReport() {
    if (!isEnabled) return;
    // CI shards upload their raw cache for the merge job to combine; generating here would
    // purge that cache (MCR removes it after generate), so they skip report generation.
    if (process.env.E2E_COVERAGE_REPORT === "false") return;
    await getMCR().generate();
}

export async function startJSCoverage(page: import("@playwright/test").Page) {
    if (!isEnabled) return;
    await page.coverage.startJSCoverage({resetOnNavigation: false});
}

export async function stopJSCoverage(page: import("@playwright/test").Page) {
    if (!isEnabled) return;
    const jsCoverage = await page.coverage.stopJSCoverage();
    if (jsCoverage.length > 0) {
        await getMCR().add(jsCoverage);
    }
}
