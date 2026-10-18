import {defineConfig} from "@hey-api/openapi-ts";

export default defineConfig({
    input: "../build/openapi.json",
    output: {
        path: "lib/generated",
        clean: true,
    },
    plugins: [
        "@hey-api/client-fetch",
        "@hey-api/sdk",
        {
            name: "@hey-api/typescript",
            // Emit runtime enum objects (const + type) instead of type-only unions, so the UI can
            // derive option lists (e.g. ServerType) from the backend contract.
            enums: "javascript",
        },
    ],
});
