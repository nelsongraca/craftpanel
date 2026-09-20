import {NextResponse} from "next/server";

const MASTER_URL = process.env.MASTER_URL ?? "http://localhost:8080";

export async function GET() {
    // Inlined at build time by Next (NEXT_PUBLIC_ vars are hardcoded into the bundle);
    // a plain env var under vitest. Gradle sets it on assembleFrontend from the git hash.
    const frontendVersion = process.env.NEXT_PUBLIC_CRAFTPANEL_BUILD_VERSION ?? "unknown";
    let masterVersion = "unknown";

    try {
        const res = await fetch(`${MASTER_URL}/health`, {cache: "no-store"});
        if (res.ok) {
            const body = await res.json();
            masterVersion = body.version ?? "unknown";
        }
    } catch {
        // master unreachable — report as unknown, still respond 200 so this stays a lightweight healthcheck
    }

    return NextResponse.json({
        status: "ok",
        frontendVersion,
        masterVersion,
        versionMismatch: frontendVersion !== "unknown" && masterVersion !== "unknown" && frontendVersion !== masterVersion,
    });
}
