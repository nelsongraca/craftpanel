"use client";

import {useCallback, useEffect, useState} from "react";

/**
 * Full-viewport overlay classes for a pane that goes fullscreen on mobile. `z-40`
 * sits above the app header and nav drawer but below dialogs (`z-50`), so dialogs
 * opened from within the pane still render on top. Safe-area padding keeps content
 * clear of the notch and home indicator under `viewport-fit: cover`.
 */
export const FULLSCREEN_PANE_CLASS =
    "fixed inset-0 z-40 bg-bg pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]";

/**
 * Toggle a pane between its normal layout and a fullscreen mobile overlay.
 *
 * `active` lets a kept-mounted pane (e.g. the Files tab) drop fullscreen and its
 * body-scroll lock when it is hidden, so neither leaks onto another tab.
 */
export function useFullscreenPane(active = true) {
    const [fullscreen, setFullscreen] = useState(false);

    useEffect(() => {
        if (!active) setFullscreen(false);
    }, [active]);

    useEffect(() => {
        if (!fullscreen) return;
        const onKeyDown = (e: KeyboardEvent) => {
            if (e.key === "Escape") setFullscreen(false);
        };
        window.addEventListener("keydown", onKeyDown);
        return () => window.removeEventListener("keydown", onKeyDown);
    }, [fullscreen]);

    useEffect(() => {
        if (!fullscreen || !active) return;
        const previous = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        return () => {
            document.body.style.overflow = previous;
        };
    }, [fullscreen, active]);

    const toggle = useCallback(() => setFullscreen((value) => !value), []);

    return {fullscreen, toggle};
}
