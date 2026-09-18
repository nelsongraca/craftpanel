import {http, HttpResponse} from "msw";

export const fileHandlers = [
    http.get("/api/servers/:id/files", ({request}) => {
        const path = new URL(request.url).searchParams.get("path") ?? "/";
        if (path === "/") {
            return HttpResponse.json({
                entries: [
                    {
                        name: "server.properties",
                        is_directory: false,
                        size_bytes: 42,
                        modified_at: "2025-06-20T10:00:00Z",
                        permissions: "rw-r--r--",
                    },
                    {
                        name: "eula.txt",
                        is_directory: false,
                        size_bytes: 11,
                        modified_at: "2025-06-20T10:00:00Z",
                        permissions: "rw-r--r--",
                    },
                    {
                        name: "world",
                        is_directory: true,
                        size_bytes: 0,
                        modified_at: "2025-06-20T10:00:00Z",
                        permissions: "rwxr-xr-x",
                    },
                ],
            });
        }
        if (path === "/world") {
            return HttpResponse.json({
                entries: [
                    {
                        name: "level.dat",
                        is_directory: false,
                        size_bytes: 512,
                        modified_at: "2025-06-20T10:00:00Z",
                        permissions: "rw-r--r--",
                    },
                ],
            });
        }
        return HttpResponse.json({entries: []});
    }),

    http.get("/api/servers/:id/files/content", ({request}) => {
        const path = new URL(request.url).searchParams.get("path") ?? "";
        if (path.endsWith("level.dat")) {
            return HttpResponse.json({encoding: "binary", content: null});
        }
        return HttpResponse.json({
            encoding: "utf-8",
            content: "motd=A CraftPanel Server\ndifficulty=normal\n",
        });
    }),

    http.put("/api/servers/:id/files/content", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.delete("/api/servers/:id/files", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.post("/api/servers/:id/files/mkdir", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.post("/api/servers/:id/files/move", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.post("/api/servers/:id/files/copy", () =>
        new HttpResponse(null, {status: 204})
    ),

    http.post("/api/servers/:id/files/upload", () =>
        HttpResponse.json({ok: true})
    ),

    http.get("/api/servers/:id/files/download", () =>
        new HttpResponse("binary-file-contents", {
            headers: {"Content-Type": "application/octet-stream"},
        })
    ),
];
