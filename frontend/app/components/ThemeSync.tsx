"use client";

import {useThemeSync} from "@/lib/hooks/useTheme";

/**
 * Mounts the theme store once, near the root. Renders nothing.
 *
 * The inline script in the root layout has already applied the correct theme before first paint;
 * this only re-applies it after React's Strict Mode dev remount and starts tracking OS changes
 * while the preference is set to `system`.
 */
export default function ThemeSync() {
    useThemeSync();
    return null;
}
