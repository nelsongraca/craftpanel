"use client";

import {useMemo, useRef, useEffect, useCallback} from "react";
import CodeMirror, {EditorView, keymap} from "@uiw/react-codemirror";
import {languageIdFromPath, languageExtension} from "@/lib/file-language";

interface Props {
  value: string;
  onChange: (value: string) => void;
  onSave: () => void;
  path: string;
  encoding?: string;
  wrap?: boolean;
}

const craftpanelTheme = EditorView.theme({
  "&": {
    backgroundColor: "var(--bg)",
    color: "var(--text-primary)",
    fontFamily: "var(--font-mono)",
    fontSize: "12px",
    lineHeight: "1.5",
    height: "100%",
  },
  ".cm-content": {
    caretColor: "var(--accent)",
    padding: "8px",
  },
  ".cm-gutters": {
    backgroundColor: "var(--surface)",
    borderRight: "1px solid var(--border)",
    minWidth: "48px",
  },
  ".cm-lineNumbers .cm-gutterElement": {
    color: "var(--text-muted)",
    paddingRight: "8px",
  },
  ".cm-activeLine": {
    backgroundColor: "var(--surface-high)",
  },
  ".cm-activeLineGutter": {
    backgroundColor: "var(--surface-higher)",
  },
  ".cm-selectionMatch": {
    backgroundColor: "var(--accent-bright)",
    opacity: "0.15",
  },
  ".cm-searchMatch": {
    backgroundColor: "var(--accent)",
    opacity: "0.3",
  },
  ".cm-searchMatch.cm-searchMatch-selected": {
    backgroundColor: "var(--accent-bright)",
    opacity: "0.5",
  },
  ".cm-tooltip": {
    backgroundColor: "var(--surface-higher)",
    border: "1px solid var(--border)",
    borderRadius: "4px",
    boxShadow: "0 4px 12px rgba(0,0,0,0.3)",
  },
  ".cm-diagnostic": {
    textDecoration: "underline wavy var(--error)",
    textDecorationThickness: "2px",
  },
  ".cm-lint-marker": {
    color: "var(--error)",
  },
  ".cm-panel": {
    backgroundColor: "var(--surface)",
    borderTop: "1px solid var(--border)",
  },
  ".cm-button": {
    backgroundColor: "var(--surface-high)",
    border: "1px solid var(--border)",
    color: "var(--text-primary)",
    borderRadius: "4px",
    padding: "2px 8px",
    "&:hover": {
      backgroundColor: "var(--surface-higher)",
    },
  },
}, {dark: true});

export function FileCodeEditor({
  value,
  onChange,
  onSave,
  path,
  encoding,
  wrap = false,
}: Props) {
  const saveRef = useRef(onSave);
  useEffect(() => {
    saveRef.current = onSave;
  }, [onSave]);

  const langId = languageIdFromPath(path);
  const langExts = languageExtension(langId);

  const saveKeymap = useMemo(
    () =>
      // eslint-disable-next-line react-hooks/exhaustive-deps, react-hooks/refs
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
    []
  );

  const extensions = useMemo(
    () => [
      craftpanelTheme,
      saveKeymap,
      ...langExts,
    ],
    [langId, saveKeymap, langExts]
  );

  const handleChange = useCallback(
    (val: string) => onChange(val),
    [onChange]
  );

  return (
    <div style={{height: "100%", width: "100%"}}>
      <CodeMirror
        value={value}
        onChange={handleChange}
        extensions={extensions}
        height="100%"
        indentWithTab={false}
        basicSetup={{
          lineNumbers: true,
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