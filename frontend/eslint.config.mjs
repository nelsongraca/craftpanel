import {defineConfig, globalIgnores} from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import prettierConfig from "eslint-config-prettier/flat";

const eslintConfig = defineConfig([
    ...nextVitals,
    ...nextTs,
    // Override default ignores of eslint-config-next.
    globalIgnores([
        // Default ignores of eslint-config-next:
        ".next/**",
        "out/**",
        "build/**",
        "next-env.d.ts",
        // Generated API client — do not lint
        "lib/generated/**",
    ]),
    {
        rules: {
            // React Compiler rule flags valid async data-fetching patterns (useCallback + useEffect).
            "react-hooks/set-state-in-effect": "off",
        },
    },
    // Must be last: disables stylistic rules that conflict with Prettier.
    prettierConfig,
]);

export default eslintConfig;
