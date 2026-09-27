import {http, HttpResponse} from "msw";

// Branding + health endpoints. Without these the shell footer falls back to
// "frontend unknown · master unknown" (wrapped onto two lines) and the header/login
// logo <img> resolves to a broken image. Both are layout-visible in mobile screenshots.

// 1x1 transparent PNG — enough for the <img> to load and reserve its box.
const LOGO_PNG = Uint8Array.from([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0a, 0x49,
    0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00,
    0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
]);

export const brandingHandlers = [
    http.get("/api/config", () =>
        HttpResponse.json({
            app_name: "CraftPanel",
            has_logo: true,
            logo_hash: "abcdef12",
            logo_url: "/api/branding/logo",
        }),
    ),

    http.get("/api/branding/logo", () =>
        HttpResponse.arrayBuffer(LOGO_PNG.buffer, {
            headers: {"Content-Type": "image/png"},
        }),
    ),

    // Shell footer / node list read build versions from the frontend healthz proxy.
    http.get("/healthz", () =>
        HttpResponse.json({
            frontendVersion: "1.0.0",
            masterVersion: "1.0.0",
            versionMismatch: false,
        }),
    ),
];
