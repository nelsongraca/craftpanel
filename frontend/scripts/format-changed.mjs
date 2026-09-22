#!/usr/bin/env node
// Ratchet formatter: only touches frontend files changed since the merge-base with the
// base ref (default origin/master), mirroring Spotless' ratchetFrom on the JVM side.
// Skips silently when the base ref is unavailable (e.g. a shallow checkout).
import {execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {readFile, writeFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import prettier from "prettier";

const write = process.argv.includes("--write");
const baseCandidates = [process.env.FORMAT_BASE, "origin/master", "origin/main", "master", "main"].filter(Boolean);
const extensions = new Set([".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".json", ".css"]);

const frontendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function tryGit(args) {
    try {
        return execFileSync("git", args, {encoding: "utf8"}).trim();
    } catch {
        return null;
    }
}

const repoRoot = tryGit(["rev-parse", "--show-toplevel"]);
if (!repoRoot) {
    console.warn("[format] not a git checkout; skipping ratchet");
    process.exit(0);
}

const base = baseCandidates.find((candidate) => tryGit(["rev-parse", "--verify", "--quiet", candidate]));
if (!base) {
    console.warn(`[format] no base ref found (tried ${baseCandidates.join(", ")}); skipping ratchet`);
    process.exit(0);
}

// Run Prettier from the frontend dir so config/plugins/ignore resolve consistently.
process.chdir(frontendDir);

const mergeBase = tryGit(["merge-base", base, "HEAD"]) ?? base;
const tracked = (tryGit(["diff", "--name-only", "--diff-filter=ACMR", mergeBase, "--"]) ?? "").split("\n");
const untracked = (tryGit(["ls-files", "--others", "--exclude-standard", "--full-name"]) ?? "").split("\n");

const candidates = [...new Set([...tracked, ...untracked])]
    .filter(Boolean)
    .filter((file) => extensions.has(path.extname(file)))
    .map((file) => path.resolve(repoRoot, file))
    .filter((file) => file.startsWith(frontendDir + path.sep))
    .filter((file) => existsSync(file));

const files = [];
for (const file of candidates) {
    const info = await prettier.getFileInfo(file);
    if (!info.ignored && info.inferredParser) files.push(file);
}

if (files.length === 0) {
    console.log(`[format] no changed formattable files vs ${base}`);
    process.exit(0);
}

const unformatted = [];
for (const file of files) {
    const options = await prettier.resolveConfig(file);
    const source = await readFile(file, "utf8");
    const formatted = await prettier.format(source, {...options, filepath: file});
    if (formatted === source) continue;
    if (write) {
        await writeFile(file, formatted);
    } else {
        unformatted.push(path.relative(frontendDir, file));
    }
}

if (write) {
    console.log(`[format] formatted ${files.length} changed file(s) vs ${base}`);
    process.exit(0);
}

if (unformatted.length > 0) {
    console.error(`[format] ${unformatted.length} changed file(s) are not formatted:`);
    for (const file of unformatted) console.error(`  ${file}`);
    console.error("[format] run `pnpm format` to fix");
    process.exit(1);
}

console.log(`[format] ${files.length} changed file(s) formatted correctly vs ${base}`);
