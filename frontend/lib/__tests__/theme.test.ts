import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {act, renderHook} from "@testing-library/react";
import {
    DEFAULT_THEME_MODE,
    getResolvedThemeSnapshot,
    getThemeModeSnapshot,
    initTheme,
    resolveManifestTheme,
    resolveTheme,
    setThemeMode,
    THEME_CHROME_COLORS,
    THEME_SPLASH_COLORS,
    THEME_STORAGE_KEY,
} from "../theme";
// The hooks are a thin binding over this store and reuse its matchMedia stub, so they are covered
// here rather than in a duplicate suite under lib/hooks/__tests__.
import {useResolvedTheme, useThemeMode} from "../hooks/useTheme";

/** Minimal matchMedia stub — jsdom does not implement it, which is why `dark` is also the fallback. */
function stubMatchMedia(matches: boolean) {
    const listeners = new Set<() => void>();
    const query = {
        matches,
        media: "(prefers-color-scheme: dark)",
        addEventListener: (_: string, listener: () => void) => listeners.add(listener),
        removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
    };
    Object.defineProperty(window, "matchMedia", {
        writable: true,
        configurable: true,
        value: vi.fn(() => query),
    });
    return {
        /** Flips the OS preference and fires the change event, as a real browser would. */
        setMatches(next: boolean) {
            query.matches = next;
            act(() => listeners.forEach((listener) => listener()));
        },
        listenerCount: () => listeners.size,
    };
}

function removeMatchMedia() {
    // @ts-expect-error -- deleting a DOM lib property to simulate environments without matchMedia.
    delete window.matchMedia;
}

/** The two tags `app/layout.tsx` server-renders via `viewport.themeColor`. */
function renderServerChromeTags() {
    document.head.innerHTML = `
        <meta name="theme-color" media="(prefers-color-scheme: light)" content="${THEME_CHROME_COLORS.light}"/>
        <meta name="theme-color" media="(prefers-color-scheme: dark)" content="${THEME_CHROME_COLORS.dark}"/>
    `;
}

function chromeTags(): HTMLMetaElement[] {
    return [...document.head.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')];
}

describe("theme", () => {
    beforeEach(() => {
        window.localStorage.clear();
        removeMatchMedia();
        document.documentElement.removeAttribute("data-theme");
    });

    afterEach(() => {
        vi.restoreAllMocks();
        // restoreAllMocks first: if a test made storage throw, this successful write also clears the
        // unsaved-mode fallback, keeping tests independent of each other's order. Wrapped in act()
        // because RTL's cleanup runs after this hook, so subscribers are still mounted.
        act(() => setThemeMode(DEFAULT_THEME_MODE));
        window.localStorage.clear();
        removeMatchMedia();
        document.documentElement.removeAttribute("data-theme");
        document.head.innerHTML = "";
    });

    describe("getStoredThemeMode", () => {
        it("defaults to system when nothing is stored", () => {
            expect(getThemeModeSnapshot()).toBe("system");
            expect(DEFAULT_THEME_MODE).toBe("system");
        });

        it("reads a stored mode", () => {
            window.localStorage.setItem(THEME_STORAGE_KEY, "light");
            expect(getThemeModeSnapshot()).toBe("light");
        });

        it("falls back to the default for an unrecognised value", () => {
            window.localStorage.setItem(THEME_STORAGE_KEY, "solarized");
            expect(getThemeModeSnapshot()).toBe("system");
        });

        it("survives storage that throws", () => {
            vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
                throw new Error("storage disabled");
            });
            expect(getThemeModeSnapshot()).toBe(DEFAULT_THEME_MODE);
        });
    });

    describe("resolution", () => {
        it("resolves system from the OS preference", () => {
            stubMatchMedia(true);
            expect(resolveTheme("system")).toBe("dark");
            stubMatchMedia(false);
            expect(resolveTheme("system")).toBe("light");
        });

        it("falls back to dark when matchMedia is unavailable", () => {
            expect(resolveTheme("system")).toBe("dark");
            expect(getResolvedThemeSnapshot()).toBe("dark");
        });

        it("ignores the OS preference for an explicit mode", () => {
            stubMatchMedia(true);
            expect(resolveTheme("light")).toBe("light");
        });
    });

    describe("setThemeMode", () => {
        it("persists the mode and applies it to the document", () => {
            setThemeMode("light");
            expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
            expect(document.documentElement.getAttribute("data-theme")).toBe("light");
        });

        it("still switches the document when storage throws", () => {
            vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
                throw new Error("quota exceeded");
            });
            setThemeMode("light");
            expect(document.documentElement.getAttribute("data-theme")).toBe("light");
            // The snapshot must agree with the document, or a re-render would compute the theme
            // from the stale persisted value and the toggle would disagree with the page.
            expect(getThemeModeSnapshot()).toBe("light");
            expect(getResolvedThemeSnapshot()).toBe("light");
        });
    });

    describe("initTheme", () => {
        it("applies the stored theme", () => {
            window.localStorage.setItem(THEME_STORAGE_KEY, "light");
            initTheme();
            expect(document.documentElement.getAttribute("data-theme")).toBe("light");
        });

        it("follows OS changes while in system mode", () => {
            const media = stubMatchMedia(false);
            initTheme();
            expect(document.documentElement.getAttribute("data-theme")).toBe("light");

            media.setMatches(true);
            expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
        });

        it("ignores OS changes once an explicit mode is chosen", () => {
            const media = stubMatchMedia(false);
            initTheme();
            setThemeMode("light");

            media.setMatches(true);
            expect(document.documentElement.getAttribute("data-theme")).toBe("light");
        });

        it("stops tracking OS changes on cleanup", () => {
            const media = stubMatchMedia(false);
            const cleanup = initTheme();
            expect(media.listenerCount()).toBe(1);
            cleanup();
            expect(media.listenerCount()).toBe(0);
        });

        it("is a no-op without matchMedia", () => {
            expect(() => initTheme()).not.toThrow();
            expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
        });
    });

    describe("hooks", () => {
        it("reports the mode and the resolved theme", () => {
            stubMatchMedia(false);
            const mode = renderHook(() => useThemeMode());
            const resolved = renderHook(() => useResolvedTheme());

            expect(mode.result.current).toBe("system");
            expect(resolved.result.current).toBe("light");
        });

        it("re-renders subscribers when the mode changes", () => {
            const {result} = renderHook(() => useResolvedTheme());
            expect(result.current).toBe("dark");

            act(() => setThemeMode("light"));
            expect(result.current).toBe("light");
        });

        it("re-renders subscribers when the OS preference changes in system mode", () => {
            const media = stubMatchMedia(false);
            const cleanup = initTheme();
            const {result} = renderHook(() => useResolvedTheme());
            expect(result.current).toBe("light");

            media.setMatches(true);
            expect(result.current).toBe("dark");
            cleanup();
        });
    });

    describe("browser chrome colour", () => {
        it("normalises the server's OS-scoped tags to the resolved theme", () => {
            renderServerChromeTags();
            initTheme();

            const tags = chromeTags();
            expect(tags).toHaveLength(2);
            // `media` must go: a tag scoped to the OS would otherwise outvote the preference.
            for (const tag of tags) {
                expect(tag.getAttribute("media")).toBeNull();
                expect(tag.getAttribute("content")).toBe(THEME_CHROME_COLORS.dark);
            }
        });

        it("lets an explicit pick outrank the OS", () => {
            renderServerChromeTags();
            stubMatchMedia(true);

            setThemeMode("light");

            for (const tag of chromeTags()) {
                expect(tag.getAttribute("content")).toBe(THEME_CHROME_COLORS.light);
            }
        });

        it("creates a tag when the document has none", () => {
            initTheme();

            expect(chromeTags()).toHaveLength(1);
            expect(chromeTags()[0].getAttribute("content")).toBe(THEME_CHROME_COLORS.dark);
        });

        it("updates the colour when the mode changes", () => {
            initTheme();

            setThemeMode("light");
            expect(chromeTags()[0].getAttribute("content")).toBe(THEME_CHROME_COLORS.light);

            setThemeMode("dark");
            expect(chromeTags()).toHaveLength(1);
            expect(chromeTags()[0].getAttribute("content")).toBe(THEME_CHROME_COLORS.dark);
        });
    });

    describe("resolveManifestTheme", () => {
        it("follows the colour-scheme hint", () => {
            expect(resolveManifestTheme("light")).toBe("light");
            expect(resolveManifestTheme("dark")).toBe("dark");
        });

        it("falls back to dark when the hint is missing", () => {
            // Safari never sends it, and Chromium omits it on a session's first request.
            expect(resolveManifestTheme(null)).toBe("dark");
            expect(resolveManifestTheme("")).toBe("dark");
        });
    });

    /**
     * `THEME_CHROME_COLORS` / `THEME_SPLASH_COLORS` are the only colour literals outside
     * `globals.css`, because a `<meta content>` and the PWA manifest cannot resolve CSS variables.
     * This is the guard that keeps them in step with the tokens they mirror.
     */
    describe("colour constants match the palette", () => {
        const css = readFileSync(resolve(process.cwd(), "app/globals.css"), "utf8");

        function declarations(selector: string): string {
            const start = css.indexOf(`${selector} {`);
            expect(start, `missing selector: ${selector}`).toBeGreaterThan(-1);
            return css.slice(start, css.indexOf("\n}", start));
        }

        function token(selector: string, name: string): string {
            const match = new RegExp(`^\\s*${name}:\\s*(.+);$`, "m").exec(declarations(selector));
            expect(match, `missing ${name} in ${selector}`).toBeTruthy();
            return match![1].trim();
        }

        it("chrome colours mirror --surface", () => {
            expect(THEME_CHROME_COLORS.dark).toBe(token(":root", "--surface"));
            expect(THEME_CHROME_COLORS.light).toBe(token('[data-theme="light"]', "--surface"));
        });

        it("splash colours mirror --bg", () => {
            expect(THEME_SPLASH_COLORS.dark).toBe(token(":root", "--bg"));
            expect(THEME_SPLASH_COLORS.light).toBe(token('[data-theme="light"]', "--bg"));
        });
    });
});
