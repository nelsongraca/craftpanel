// Shared monocart-coverage-reports options. Kept in a plain .mjs module so both the
// Playwright coverage hook (coverage.ts) and the CI merge script (merge-coverage.mjs)
// apply identical filters when collecting and merging shard coverage.
export const coverageOptions = {
    outputDir: "build/reports/coverage-e2e",
    reports: ["lcovonly", "html", "text"],
    entryFilter: (entry) => entry.url.includes("localhost:3000"),
    sourceFilter: (sourcePath) => {
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
