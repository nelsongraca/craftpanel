import MCR from "monocart-coverage-reports";

const isEnabled = !!process.env.E2E_COVERAGE;

const coverageOptions = {
  outputDir: "build/reports/coverage-e2e",
  reports: ["lcovonly" as const, "html" as const, "text" as const],
  entryFilter: (entry: { url: string }) => entry.url.includes("localhost:3000"),
  sourceFilter: (sourcePath: string) => {
    // Only report the app's own sources. Turbopack serves Next/React/the HMR runtime
    // from paths that are not under node_modules/.next once source maps are unpacked
    // (next/dist/..., next/src/..., [turbopack]/..., react-dom, react, scheduler), so a
    // glob on node_modules alone leaves ~72% of the denominator as un-instrumentable
    // framework code.
    const p = sourcePath.replace(/\\/g, "/");
    if (/(^|\/)node_modules\//.test(p)) return false;
    if (/(^|\/)\.next\//.test(p) || p.includes("/_next/")) return false;
    if (p.includes("lib/generated/")) return false;
    if (p.includes("[turbopack]")) return false;
    if (p.includes("next/dist/") || p.includes("next/src/")) return false;
    if (p.includes("react-dom") || p.includes("react-server")) return false;
    if (p.includes("/react/cjs/react.production")) return false;
    if (p.includes("/scheduler/")) return false;
    return true;
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
