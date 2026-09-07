import MCR from "monocart-coverage-reports";

const isEnabled = !!process.env.E2E_COVERAGE;

const coverageOptions = {
  outputDir: "build/reports/coverage-e2e",
  reports: ["lcovonly" as const, "html" as const, "text" as const],
  entryFilter: (entry: { url: string }) => entry.url.includes("localhost:3000"),
  sourceFilter: {
    "**/node_modules/**": false,
    "**/.next/**": false,
    "**/lib/generated/**": false,
    "**": true,
  },
};

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
  await getMCR().generate();
}

export async function startJSCoverage(page: import("@playwright/test").Page) {
  if (!isEnabled) return;
  await page.coverage.startJSCoverage({ resetOnNavigation: false });
}

export async function stopJSCoverage(page: import("@playwright/test").Page) {
  if (!isEnabled) return;
  const jsCoverage = await page.coverage.stopJSCoverage();
  if (jsCoverage.length > 0) {
    await getMCR().add(jsCoverage);
  }
}
