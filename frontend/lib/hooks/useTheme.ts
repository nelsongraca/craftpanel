"use client";

import {useEffect, useLayoutEffect, useSyncExternalStore} from "react";
import {
    DEFAULT_THEME_MODE,
    getResolvedThemeSnapshot,
    getThemeModeSnapshot,
    initTheme,
    subscribeTheme,
    type ResolvedTheme,
    type ThemeMode,
} from "@/lib/theme";

/**
 * `useLayoutEffect` warns when it runs during SSR, so client components fall back to `useEffect`
 * on the server pass. The inline script in the root layout has already painted the right theme by
 * then, so the fallback cannot cause a flash.
 */
const useIsomorphicLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

/** The user's chosen mode (`dark` / `light` / `system`). */
export function useThemeMode(): ThemeMode {
    return useSyncExternalStore(subscribeTheme, getThemeModeSnapshot, () => DEFAULT_THEME_MODE);
}

/** The mode with `system` already resolved to a concrete `dark` or `light`. */
export function useResolvedTheme(): ResolvedTheme {
    // The server renders `data-theme="dark"`, so the hydration pass must agree with it.
    return useSyncExternalStore(subscribeTheme, getResolvedThemeSnapshot, () => "dark");
}

/** Applies the stored theme and tracks OS changes. Mount exactly once, near the root. */
export function useThemeSync() {
    useIsomorphicLayoutEffect(() => initTheme(), []);
}
