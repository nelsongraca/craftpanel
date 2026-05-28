import {defineConfig} from "@hey-api/openapi-ts"

export default defineConfig({
    input: "../openapi.json",
    output: {
        path: "lib/generated",
        clean: true,
    },
    plugins: ["@hey-api/client-fetch", "@hey-api/sdk", "@hey-api/typescript"],
})
