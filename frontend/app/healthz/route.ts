import {NextResponse} from "next/server";

const MASTER_URL = process.env.MASTER_URL ?? "http://localhost:8080";

export async function GET() {
    const frontendVersion = process.env.APP_VERSION ?? "unknown";
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
