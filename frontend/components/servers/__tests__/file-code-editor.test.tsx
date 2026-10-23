import {act, fireEvent, render} from "@testing-library/react";
import {createRef} from "react";
import {EditorView} from "@codemirror/view";
import {searchPanelOpen} from "@codemirror/search";
import {FileCodeEditor, type FileCodeEditorHandle} from "../file-code-editor";

function mount() {
    const result = render(
        <FileCodeEditor value="hello world" onChange={() => {}} onSave={() => {}} path="server.properties" />,
    );
    const editor = result.container.querySelector(".cm-editor") as HTMLElement;
    const view = EditorView.findFromDOM(editor);
    if (!view) throw new Error("CodeMirror view was not created");
    return {...result, view, content: result.container.querySelector(".cm-content") as HTMLElement};
}

function openFind(content: HTMLElement) {
    act(() => {
        content.focus();
        fireEvent.keyDown(content, {key: "f", code: "KeyF", ctrlKey: true});
    });
}

describe("FileCodeEditor find panel", () => {
    it("opens the search panel on Ctrl+F", () => {
        const {view, content, container} = mount();
        openFind(content);
        expect(searchPanelOpen(view.state)).toBe(true);
        // The panel must actually mount: a second copy of @codemirror/view makes
        // `openSearchPanel` succeed while the editor never sees its `showPanel` facet.
        expect(container.querySelector(".cm-search")).toBeTruthy();
    });

    it("keeps the search panel open when the parent re-renders", () => {
        // Mirrors the Files tab: the parent passes a fresh `onChange` on every render.
        const {view, content, rerender} = mount();
        openFind(content);
        expect(searchPanelOpen(view.state)).toBe(true);

        act(() => {
            rerender(
                <FileCodeEditor value="hello world" onChange={() => {}} onSave={() => {}} path="server.properties" />,
            );
        });

        // @uiw/react-codemirror reconfigures the editor when a prop identity changes; that
        // reconfigure used to tear the search state added by `openSearchPanel` down with it.
        expect(searchPanelOpen(view.state)).toBe(true);
    });

    it("opens the search panel through the imperative handle (touch devices have no Ctrl+F)", () => {
        const ref = createRef<FileCodeEditorHandle>();
        const {container} = render(
            <FileCodeEditor
                ref={ref}
                value="hello world"
                onChange={() => {}}
                onSave={() => {}}
                path="server.properties"
            />,
        );
        const editor = container.querySelector(".cm-editor") as HTMLElement;
        const view = EditorView.findFromDOM(editor);
        if (!view) throw new Error("CodeMirror view was not created");

        act(() => ref.current?.openSearch());

        expect(searchPanelOpen(view.state)).toBe(true);
        expect(container.querySelector(".cm-search")).toBeTruthy();
    });
});
