"use client";

import {Check, Monitor, Moon, Sun, type LucideIcon} from "lucide-react";
import {DropdownMenuItem, DropdownMenuLabel} from "@/components/ui/dropdown-menu";
import {setThemeMode, THEME_MODES, type ThemeMode} from "@/lib/theme";
import {useThemeMode} from "@/lib/hooks/useTheme";

const MODE_LABELS: Record<ThemeMode, string> = {
    dark: "Dark",
    light: "Light",
    system: "System",
};

const MODE_ICONS: Record<ThemeMode, LucideIcon> = {
    dark: Moon,
    light: Sun,
    system: Monitor,
};

const MODE_HINTS: Record<ThemeMode, string> = {
    dark: "Always use the dark palette.",
    light: "Always use the light palette.",
    system: "Follow your operating system setting.",
};

/** Segmented control for the account page's Theme section. */
export function ThemeToggle() {
    const mode = useThemeMode();

    return (
        <div
            className="inline-flex rounded-md border border-border bg-surface-high p-0.5"
            role="group"
            aria-label="Theme"
        >
            {THEME_MODES.map((value) => {
                const Icon = MODE_ICONS[value];
                const active = mode === value;
                return (
                    <button
                        key={value}
                        type="button"
                        aria-pressed={active}
                        onClick={() => setThemeMode(value)}
                        className={[
                            "flex items-center gap-1.5 rounded px-3 py-1.5",
                            "font-heading text-xs font-bold tracking-wider uppercase transition-colors",
                            active ? "bg-accent text-on-accent" : "text-text-dim hover:text-text-primary",
                        ].join(" ")}
                    >
                        <Icon size={13} strokeWidth={2} />
                        {MODE_LABELS[value]}
                    </button>
                );
            })}
        </div>
    );
}

/** Three menu entries for the shell's user dropdown. */
export function ThemeMenuItems() {
    const mode = useThemeMode();

    return (
        <>
            <DropdownMenuLabel className="text-text-muted">Theme</DropdownMenuLabel>
            {THEME_MODES.map((value) => {
                const Icon = MODE_ICONS[value];
                return (
                    <DropdownMenuItem key={value} onClick={() => setThemeMode(value)} className="text-text-primary">
                        <Icon size={13} strokeWidth={2} />
                        {MODE_LABELS[value]}
                        {mode === value && <Check size={13} strokeWidth={2.5} className="ml-auto text-accent" />}
                    </DropdownMenuItem>
                );
            })}
        </>
    );
}

/** Helper text for the account page — kept next to the labels it describes. */
export function themeHint(mode: ThemeMode): string {
    return MODE_HINTS[mode];
}
