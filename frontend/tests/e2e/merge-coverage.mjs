// Merges the raw coverage caches uploaded by each CI shard into a single report.
// MCR deletes its cache after generating, so shards run with E2E_COVERAGE_REPORT=false
// and only produce raw data; this script combines them.
import fs from "node:fs";
import path from "node:path";
import MCR from "monocart-coverage-reports";
import {coverageOptions} from "./coverage-options.mjs";

/** Directories that directly contain MCR raw `coverage-*.json` files. */
function findCacheDirs(root) {
    const found = [];
    const walk = (dir) => {
        let entries;
        try {
            entries = fs.readdirSync(dir, {withFileTypes: true});
        } catch {
            return;
        }
        if (entries.some((e) => e.isFile() && /^coverage-.*\.json$/.test(e.name))) {
            found.push(dir);
        }
        for (const entry of entries) {
            if (entry.isDirectory()) walk(path.join(dir, entry.name));
        }
    };
    if (fs.existsSync(root)) walk(root);
    return found;
}

const root = process.env.E2E_COVERAGE_INPUT_DIR || "build/coverage-shards";
const dirs = findCacheDirs(root);
if (dirs.length === 0) {
    console.error(`[merge-e2e-coverage] no raw coverage data found under ${root}`);
    process.exit(1);
}

console.log(`[merge-e2e-coverage] merging ${dirs.length} shard cache dir(s)`);
const mcr = MCR({...coverageOptions, inputDir: dirs.join(",")});
await mcr.generate();
