import {type NextRequest, NextResponse} from "next/server";

async function proxy(req: NextRequest): Promise<NextResponse> {
    const masterUrl = process.env.MASTER_URL ?? "http://localhost:8080";
    const path = req.nextUrl.pathname;
    const search = req.nextUrl.search;
    const target = `${masterUrl}${path}${search}`;

    const headers = new Headers(req.headers);
    headers.delete("host");
    headers.delete("origin");
    headers.delete("referer");

    const upstream = await fetch(target, {
        method: req.method,
        headers,
        body: req.method !== "GET" && req.method !== "HEAD" ? req.body : undefined,
        duplex: "half",
    } as RequestInit);

    const responseHeaders = new Headers(upstream.headers);
    responseHeaders.delete("transfer-encoding");

    return new NextResponse(upstream.body, {
        status: upstream.status,
        headers: responseHeaders,
    });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
