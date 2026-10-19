import {describe, it, expect, vi, beforeEach, afterEach} from "vitest";
import {render, screen} from "@testing-library/react";
import type {ReactNode} from "react";

vi.mock("next/font/local", () => ({
    default: ({variable}: {variable: string}) => ({variable}),
}));

vi.mock("@/lib/auth-context", () => ({
    AuthProvider: ({children}: {children: ReactNode}) => <div data-testid="auth-provider">{children}</div>,
}));

import RootLayout, {viewport} from "../layout";
import {THEME_CHROME_COLORS} from "@/lib/theme";

// RootLayout renders <html>; jsdom's render container is a <div>, which React flags.
beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe("RootLayout", () => {
    it("renders children inside the AuthProvider", () => {
        render(
            <RootLayout>
                <main data-testid="content">Hello</main>
            </RootLayout>,
        );
        expect(screen.getByTestId("auth-provider")).toBeInTheDocument();
        expect(screen.getByTestId("content")).toHaveTextContent("Hello");
    });

    it("server-renders the dark theme as the default", () => {
        render(
            <RootLayout>
                <span />
            </RootLayout>,
        );
        const html = document.documentElement;
        expect(html.lang).toBe("en");
        expect(html.getAttribute("data-theme")).toBe("dark");
        expect(html.classList).toContain("antialiased");
    });

    it("inlines the theme init script ahead of the body", () => {
        render(
            <RootLayout>
                <span />
            </RootLayout>,
        );
        const html = document.documentElement.outerHTML;
        expect(html).toContain("craftpanel:theme");
        expect(html).toContain("prefers-color-scheme: dark");
    });

    it("declares OS-scoped theme colours for the browser chrome", () => {
        // Two media-scoped tags, so the chrome is right before hydration and with JS disabled;
        // lib/theme.ts collapses them to one once the stored preference is known.
        expect(viewport.themeColor).toEqual([
            {media: "(prefers-color-scheme: light)", color: THEME_CHROME_COLORS.light},
            {media: "(prefers-color-scheme: dark)", color: THEME_CHROME_COLORS.dark},
        ]);
    });

    it("collapses the chrome tags to the resolved theme once mounted", () => {
        render(
            <RootLayout>
                <span />
            </RootLayout>,
        );
        // ThemeSync normalises whatever the head already holds, so assert over all of them.
        const tags = [...document.head.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')];
        expect(tags.length).toBeGreaterThan(0);
        for (const tag of tags) {
            expect(tag.getAttribute("media")).toBeNull();
            // jsdom has no matchMedia, so the resolver falls back to the default dark palette.
            expect(tag.getAttribute("content")).toBe(THEME_CHROME_COLORS.dark);
        }
    });

    it("sets the API origin script from PUBLIC_API_URL", () => {
        vi.stubEnv("PUBLIC_API_URL", "https://api.example.com");
        render(
            <RootLayout>
                <span />
            </RootLayout>,
        );
        expect(document.documentElement.outerHTML).toContain('window.__API_URL__="https://api.example.com"');
        vi.unstubAllEnvs();
    });

    it("defaults API origin to empty string", () => {
        vi.stubEnv("PUBLIC_API_URL", "");
        render(
            <RootLayout>
                <span />
            </RootLayout>,
        );
        expect(document.documentElement.outerHTML).toContain('window.__API_URL__=""');
        vi.unstubAllEnvs();
    });
});
