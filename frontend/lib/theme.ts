/**
 * Theme preference store.
 *
 * The preference is per-browser (`localStorage`) and defaults to following the OS. `system` is
 * resolved to a concrete `dark`/`light` value before it reaches the DOM, so the CSS only ever
 * needs `[data-theme="dark" | "light"]` — no media query in the stylesheet.
 *
 * Deliberately **React-free and server-safe**: `app/layout.tsx` and `app/manifest.ts` are Server
 * Components/routes and need `THEME_CHROME_COLORS` / `THEME_SPLASH_COLORS` for the metadata they
 * render. The React bindings live in `lib/hooks/useTheme.ts`; keeping hooks out of here is what
 * lets the server import this module at all.
 *
 * The resolution logic is duplicated in the inline script in `app/layout.tsx`, which runs during
 * HTML parsing to avoid a flash of the wrong theme. Keep the two in sync — this module is the
 * source of truth and `lib/__tests__/theme.test.ts` pins the behaviour.
 */

export type ThemeMode = "dark" | "light" | "system";
export type ResolvedTheme = "dark" | "light";

export const THEME_MODES: readonly ThemeMode[] = ["dark", "light", "system"];

export const THEME_STORAGE_KEY = "craftpanel:theme";
export const THEME_ATTRIBUTE = "data-theme";
export const DEFAULT_THEME_MODE: ThemeMode = "system";

/**
 * Colours the browser paints *around* the app — address bar, Android toolbar, PWA title bar.
 *
 * These mirror `--surface` in `globals.css`, which is what the app header uses, so the browser
 * chrome meets the app without a seam. They have to be literals: a `<meta content>` and the PWA
 * manifest cannot resolve CSS variables. `theme.test.ts` fails if they drift from the tokens.
 */
export const THEME_CHROME_COLORS: Record<ResolvedTheme, string> = {
    dark: "#1c1917",
    light: "#ffffff",
};

/** PWA splash background — mirrors `--bg` in `globals.css`. Same drift guard as above. */
export const THEME_SPLASH_COLORS: Record<ResolvedTheme, string> = {
    dark: "#0e0d0c",
    light: "#faf8f5",
};

const DARK_QUERY = "(prefers-color-scheme: dark)";

function isThemeMode(value: unknown): value is ThemeMode {
    return value === "dark" || value === "light" || value === "system";
}

function supportsMatchMedia(): boolean {
    return typeof window !== "undefined" && typeof window.matchMedia === "function";
}

/** The persisted mode, or the default when nothing valid is stored. */
export function getStoredThemeMode(): ThemeMode {
    if (typeof window === "undefined") return DEFAULT_THEME_MODE;
    try {
        const raw = window.localStorage.getItem(THEME_STORAGE_KEY);
        return isThemeMode(raw) ? raw : DEFAULT_THEME_MODE;
    } catch {
        // Storage can throw outright (Safari private mode, storage disabled by policy).
        return DEFAULT_THEME_MODE;
    }
}

/** The OS preference. Falls back to `dark` where `matchMedia` is unavailable (jsdom, SSR). */
export function getSystemTheme(): ResolvedTheme {
    if (!supportsMatchMedia()) return "dark";
    return window.matchMedia(DARK_QUERY).matches ? "dark" : "light";
}

export function resolveTheme(mode: ThemeMode): ResolvedTheme {
    return mode === "system" ? getSystemTheme() : mode;
}

/**
 * Maps a `Sec-CH-Prefers-Color-Scheme` value (or its absence) onto a theme.
 *
 * The hint is Chromium-only and opt-in, and it is still missing on the first request of a session,
 * so `dark` — the app's own default — is the fallback.
 */
export function resolveManifestTheme(prefersColorScheme: string | null): ResolvedTheme {
    return prefersColorScheme === "light" ? "light" : "dark";
}

// ── Store ─────────────────────────────────────────────────────────────────────

const listeners = new Set<() => void>();

/**
 * Fallback mode, set only when the preference could not be persisted.
 *
 * Without it a failed write is a silent no-op: the snapshot re-reads `localStorage`, gets the old
 * value back, and the toggle disagrees with the page. It stays `null` whenever storage worked, so
 * `localStorage` remains the single source of truth.
 */
let unsavedMode: ThemeMode | null = null;

function notify() {
    listeners.forEach((listener) => listener());
}

/**
 * Snapshots are recomputed rather than cached: both underlying sources (`localStorage` and
 * `matchMedia`) are cheap reads that already return stable primitives, and `useSyncExternalStore`
 * compares snapshots with `Object.is` — so there is nothing to gain from a cache that could go
 * stale, and caching here would only create a second source of truth.
 */
export function getThemeModeSnapshot(): ThemeMode {
    return unsavedMode ?? getStoredThemeMode();
}

export function getResolvedThemeSnapshot(): ResolvedTheme {
    return resolveTheme(getThemeModeSnapshot());
}

function applyTheme(resolved: ResolvedTheme) {
    const root = document.documentElement;
    if (root.getAttribute(THEME_ATTRIBUTE) !== resolved) {
        root.setAttribute(THEME_ATTRIBUTE, resolved);
    }
    applyThemeColor(resolved);
}

/**
 * Repoints the browser's own chrome at the active theme.
 *
 * The server ships `prefers-color-scheme`-scoped tags, so the chrome is already correct for
 * `system` users and stays correct with JS disabled. Once the stored preference is known those are
 * normalised: `media` is dropped so every tag applies unconditionally, which stops the OS from
 * outvoting an explicit `light`/`dark` pick regardless of which tag the browser picks.
 *
 * Tags are updated in place rather than replaced — during hydration the server-rendered ones also
 * belong to React's `<head>` tree, and removing them makes React's unmount cleanup throw.
 */
function applyThemeColor(resolved: ResolvedTheme) {
    const head = document.head;
    const color = THEME_CHROME_COLORS[resolved];
    const tags = head.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]');

    if (tags.length === 0) {
        const tag = document.createElement("meta");
        tag.name = "theme-color";
        tag.content = color;
        head.appendChild(tag);
        return;
    }

    tags.forEach((tag) => {
        tag.removeAttribute("media");
        tag.content = color;
    });
}

export function subscribeTheme(listener: () => void): () => void {
    listeners.add(listener);
    return () => {
        listeners.delete(listener);
    };
}

/** Persists the mode, applies it immediately and notifies subscribers. */
export function setThemeMode(next: ThemeMode) {
    let persisted = true;
    try {
        window.localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
        persisted = false;
    }
    // Only fall back to memory if the value could not be stored, so `localStorage` stays
    // authoritative everywhere else.
    unsavedMode = persisted ? null : next;
    applyTheme(getResolvedThemeSnapshot());
    notify();
}

/**
 * Re-applies the stored theme and starts tracking OS changes while in `system` mode.
 *
 * The re-apply is not redundant: in development React's Strict Mode remounts and resets `<html>`
 * to only the attributes it manages from JSX, wiping the one the inline script set.
 *
 * Returns a cleanup function that stops tracking.
 */
export function initTheme(): () => void {
    applyTheme(getResolvedThemeSnapshot());

    if (!supportsMatchMedia()) return () => {};

    const query = window.matchMedia(DARK_QUERY);
    const onSystemChange = () => {
        if (getThemeModeSnapshot() !== "system") return;
        applyTheme(getResolvedThemeSnapshot());
        notify();
    };

    query.addEventListener("change", onSystemChange);
    return () => query.removeEventListener("change", onSystemChange);
}
