import {describe, expect, it} from "vitest";
import {collectDroppedItems, itemsFromFileList, normalizeUploadPath, toUploadFile} from "../upload-drop";

function file(name: string, content = "x"): File {
    return new File([content], name, {type: "text/plain"});
}

function withRelativePath(file: File, relativePath: string): File {
    Object.defineProperty(file, "webkitRelativePath", {value: relativePath});
    return file;
}

interface FakeDirectory {
    batches: FileSystemEntry[][];
    calls: number;
}

function fakeFileEntry(name: string, content = "x"): FileSystemEntry {
    return {
        isFile: true,
        isDirectory: false,
        name,
        file: (resolve: (file: File) => void) => resolve(file(name, content)),
    } as unknown as FileSystemFileEntry;
}

function fakeDirectoryEntry(name: string, directory: FakeDirectory): FileSystemEntry {
    return {
        isFile: false,
        isDirectory: true,
        name,
        createReader: () => ({
            readEntries: (resolve: (entries: FileSystemEntry[]) => void) => {
                const batch = directory.batches[directory.calls++] ?? [];
                resolve(batch);
            },
        }),
    } as unknown as FileSystemDirectoryEntry;
}

function fakeDataTransfer(entries: FileSystemEntry[]): DataTransfer {
    return {
        items: entries.map((entry) => ({
            kind: "file",
            webkitGetAsEntry: () => entry,
        })),
    } as unknown as DataTransfer;
}

describe("normalizeUploadPath", () => {
    it("collapses backslashes and removes leading, empty and dot segments", () => {
        expect(normalizeUploadPath("\\folder\\\\sub/./file.txt")).toBe("folder/sub/file.txt");
        expect(normalizeUploadPath("/folder/file.txt")).toBe("folder/file.txt");
        expect(normalizeUploadPath("../folder/file.txt")).toBe("folder/file.txt");
    });

    it("returns an empty string for a path with no usable segments", () => {
        expect(normalizeUploadPath("./")).toBe("");
    });
});

describe("itemsFromFileList", () => {
    it("uses webkitRelativePath for folder selections", () => {
        const items = itemsFromFileList([
            withRelativePath(file("config.yml"), "MyPlugin/config.yml"),
            file("loose.txt"),
        ]);

        expect(items.map((item) => item.relativePath)).toEqual(["MyPlugin/config.yml", "loose.txt"]);
    });
});

describe("toUploadFile", () => {
    it("keeps the original File when the path is just the name", () => {
        const original = file("a.txt");
        expect(toUploadFile({file: original, relativePath: "a.txt"})).toBe(original);
    });

    it("wraps the File so multipart carries the nested path as its filename", () => {
        const original = file("a.txt");
        const wrapped = toUploadFile({file: original, relativePath: "nested/a.txt"});

        expect(wrapped).not.toBe(original);
        expect(wrapped.name).toBe("nested/a.txt");
        expect(wrapped.size).toBe(1);
    });
});

describe("collectDroppedItems", () => {
    it("walks dropped folders and drains readEntries batches", async () => {
        const directory: FakeDirectory = {
            calls: 0,
            batches: [[fakeFileEntry("a.txt"), fakeFileEntry("b.txt")], [fakeFileEntry("c.txt")], []],
        };

        const items = await collectDroppedItems(fakeDataTransfer([fakeDirectoryEntry("world", directory)]));

        expect(directory.calls).toBe(3);
        expect(items.map((item) => item.relativePath)).toEqual(["world/a.txt", "world/b.txt", "world/c.txt"]);
    });

    it("keeps top-level dropped files at their name", async () => {
        const items = await collectDroppedItems(fakeDataTransfer([fakeFileEntry("server.properties")]));
        expect(items.map((item) => item.relativePath)).toEqual(["server.properties"]);
    });

    it("falls back to getAsFile when the browser has no entry API", async () => {
        const fallback = file("fallback.txt");
        const dataTransfer = {
            items: [{kind: "file", webkitGetAsEntry: () => null, getAsFile: () => fallback}],
        } as unknown as DataTransfer;

        const items = await collectDroppedItems(dataTransfer);
        expect(items).toEqual([{file: fallback, relativePath: "fallback.txt"}]);
    });
});
