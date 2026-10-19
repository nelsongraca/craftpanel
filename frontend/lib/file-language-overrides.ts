import {isLanguageId, type LanguageId} from "./file-language";

/**
 * Per-browser syntax-highlighting overrides, keyed by file path.
 *
 * Detection by extension (`languageIdFromPath`) is wrong for files that carry no
 * meaningful extension — e.g. a mod's extension-less TOML config — so the user can
 * pin a language to a path. Storage is best-effort `localStorage`, mirroring
 * `lib/console-history.ts`: any failure degrades to "no override" rather than throwing.
 */
export const LANGUAGE_OVERRIDES_STORAGE_KEY = "craftpanel:file-language";

export type LanguageOverrides = Record<string, LanguageId>;

/** Load persisted overrides, dropping anything malformed or unknown. */
export function loadLanguageOverrides(): LanguageOverrides {
    if (typeof window === "undefined") return {};
    try {
        const raw = window.localStorage.getItem(LANGUAGE_OVERRIDES_STORAGE_KEY);
        if (!raw) return {};
        const parsed: unknown = JSON.parse(raw);
        if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
        const result: LanguageOverrides = {};
        for (const [path, lang] of Object.entries(parsed)) {
            if (isLanguageId(lang)) result[path] = lang;
        }
        return result;
    } catch {
        return {};
    }
}

/**
 * Return a new overrides map with `path` set to `lang`, or (when `lang` is `null`)
 * with `path` removed to fall back to extension detection. Pure — the caller persists.
 */
export function setLanguageOverride(
    overrides: LanguageOverrides,
    path: string,
    lang: LanguageId | null,
): LanguageOverrides {
    if (lang === null) {
        if (!(path in overrides)) return overrides;
        const next = {...overrides};
        delete next[path];
        return next;
    }
    return {...overrides, [path]: lang};
}

/** Persist overrides. Best-effort: quota/private-mode failures are swallowed. */
export function saveLanguageOverrides(overrides: LanguageOverrides): void {
    if (typeof window === "undefined") return;
    try {
        window.localStorage.setItem(LANGUAGE_OVERRIDES_STORAGE_KEY, JSON.stringify(overrides));
    } catch {
        // storage unavailable — override persistence is best-effort
    }
}
