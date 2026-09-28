import type {Terminal} from "@xterm/xterm";

/** Vertical travel (px) before a touch drag is treated as a scroll rather than a tap. */
const DEAD_ZONE_PX = 10;

interface ScrollStep {
    /** Whole rows to scroll for this step. */
    lines: number;
    /** Sub-row pixel travel carried into the next step. */
    remainderPx: number;
}

/**
 * Convert accumulated pixel travel into whole terminal rows, carrying the
 * sub-row remainder so slow drags do not lose movement. Pure and unit-tested.
 */
export function accumulateScroll(remainderPx: number, deltaPx: number, rowHeightPx: number): ScrollStep {
    if (!(rowHeightPx > 0)) return {lines: 0, remainderPx: 0};
    const total = remainderPx + deltaPx;
    const lines = Math.trunc(total / rowHeightPx);
    return {lines, remainderPx: total - lines * rowHeightPx};
}

/**
 * Height of a single terminal row. Measured from the rendered screen (rows are
 * uniform) and falling back to font metrics before the first paint.
 */
export function measureRowHeight(host: HTMLElement, term: Terminal): number {
    const screen = host.querySelector<HTMLElement>(".xterm-screen");
    if (screen && term.rows > 0) {
        const height = screen.getBoundingClientRect().height;
        if (height > 0) return height / term.rows;
    }
    const fontSize = term.options.fontSize ?? 13;
    const lineHeight = term.options.lineHeight ?? 1;
    return fontSize * lineHeight;
}

/**
 * xterm 6.0.0 wires no touch-scroll path: the `.xterm-screen` canvas sits over
 * the scrollable viewport, so a one-finger drag never reaches it and scrollback
 * is unreachable on mobile. This translates a vertical drag on the host element
 * into `term.scrollLines()`, taking over only past a small dead zone so taps
 * (focus) and long-press (native selection) still pass through untouched.
 *
 * Returns a cleanup function that removes every listener.
 */
export function attachXtermTouchScroll(host: HTMLElement, term: Terminal): () => void {
    let startY = 0;
    let lastY = 0;
    let remainderPx = 0;
    let rowHeightPx = 0;
    let active = false;
    let ignore = false;

    function onTouchStart(e: TouchEvent) {
        active = false;
        ignore = e.touches.length !== 1;
        if (ignore) return;
        startY = lastY = e.touches[0].clientY;
        remainderPx = 0;
    }

    function onTouchMove(e: TouchEvent) {
        if (ignore) return;
        if (e.touches.length !== 1) {
            ignore = true;
            active = false;
            return;
        }
        const y = e.touches[0].clientY;
        if (!active) {
            if (Math.abs(y - startY) <= DEAD_ZONE_PX) return;
            active = true;
            rowHeightPx = measureRowHeight(host, term);
        }
        // Claimed as a scroll: block native panning so the page/keyboard do not fight us.
        e.preventDefault();
        const step = accumulateScroll(remainderPx, y - lastY, rowHeightPx);
        remainderPx = step.remainderPx;
        lastY = y;
        if (step.lines !== 0) term.scrollLines(-step.lines);
    }

    function onTouchEnd() {
        active = false;
    }

    host.addEventListener("touchstart", onTouchStart, {passive: true});
    host.addEventListener("touchmove", onTouchMove, {passive: false});
    host.addEventListener("touchend", onTouchEnd, {passive: true});
    host.addEventListener("touchcancel", onTouchEnd, {passive: true});

    return () => {
        host.removeEventListener("touchstart", onTouchStart);
        host.removeEventListener("touchmove", onTouchMove);
        host.removeEventListener("touchend", onTouchEnd);
        host.removeEventListener("touchcancel", onTouchEnd);
    };
}
