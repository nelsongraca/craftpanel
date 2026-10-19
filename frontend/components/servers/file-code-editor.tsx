"use client";

import {useMemo, useRef, useEffect, useCallback} from "react";
import CodeMirror, {EditorView, keymap} from "@uiw/react-codemirror";
import {darculaInit} from "@uiw/codemirror-theme-darcula";
import {githubLightInit} from "@uiw/codemirror-theme-github";
import {languageIdFromPath, languageExtension} from "@/lib/file-language";
import {useResolvedTheme} from "@/lib/hooks/useTheme";

interface Props {
    value: string;
    onChange: (value: string) => void;
    onSave: () => void;
    path: string;
    wrap?: boolean;
}

/**
 * Gutter colours resolve through the CraftPanel tokens so the editor chrome tracks the palette;
 * each theme keeps its own syntax colours.
 */
const EDITOR_CHROME = {
    fontFamily: "var(--font-mono)",
    fontSize: "13px",
    gutterBackground: "var(--surface)",
    gutterForeground: "var(--text-dim)",
    gutterActiveForeground: "var(--text-primary)",
};

const darculaTheme = darculaInit({settings: EDITOR_CHROME});
const githubLightTheme = githubLightInit({settings: EDITOR_CHROME});

const editorChrome = EditorView.theme({
    ".cm-gutters": {
        borderRight: "1px solid var(--border)",
    },
    ".cm-searchMatch": {
        backgroundColor: "var(--accent)",
        opacity: "0.3",
    },
    ".cm-searchMatch.cm-searchMatch-selected": {
        backgroundColor: "var(--accent-bright)",
        opacity: "0.5",
    },
    ".cm-diagnostic": {
        textDecoration: "underline wavy var(--error)",
        textDecorationThickness: "2px",
    },
    ".cm-lint-marker": {
        color: "var(--error)",
    },
});

export function FileCodeEditor({value, onChange, onSave, path, wrap = false}: Props) {
    const saveRef = useRef(onSave);
    useEffect(() => {
        saveRef.current = onSave;
    }, [onSave]);

    const langId = languageIdFromPath(path);
    const langExts = useMemo(() => languageExtension(langId), [langId]);
    const editorTheme = useResolvedTheme() === "light" ? githubLightTheme : darculaTheme;

    const saveKeymap = useMemo(
        () =>
            // eslint-disable-next-line react-hooks/refs
            keymap.of([
                {
                    key: "Mod-s",
                    preventDefault: true,
                    run: () => {
                        saveRef.current();
                        return true;
                    },
                },
            ]),
        [],
    );

    const extensions = useMemo(
        () => [editorChrome, saveKeymap, ...(wrap ? [EditorView.lineWrapping] : []), ...langExts],
        [wrap, saveKeymap, langExts],
    );

    const handleChange = useCallback((val: string) => onChange(val), [onChange]);

    return (
        <div style={{height: "100%", width: "100%"}}>
            <CodeMirror
                className="h-full"
                value={value}
                onChange={handleChange}
                theme={editorTheme}
                extensions={extensions}
                height="100%"
                indentWithTab={false}
                basicSetup={{
                    lineNumbers: true,
                    syntaxHighlighting: false,
                    highlightActiveLine: true,
                    highlightSelectionMatches: true,
                    closeBracketsKeymap: true,
                    defaultKeymap: true,
                    searchKeymap: true,
                    historyKeymap: true,
                    foldKeymap: true,
                    completionKeymap: true,
                    lintKeymap: true,
                }}
            />
        </div>
    );
}
