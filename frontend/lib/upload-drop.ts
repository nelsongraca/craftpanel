/** One file queued for upload, with the path it should get relative to the destination directory. */
export interface UploadItem {
    file: File;
    /** Path relative to the upload destination, e.g. `plugins/MyPlugin/config.yml`. */
    relativePath: string;
}

/** Collapses separators and drops empty / `.` / `..` segments. Browser-supplied paths are already clean. */
export function normalizeUploadPath(path: string): string {
    return path
        .replace(/\\/g, "/")
        .split("/")
        .filter((segment) => segment.length > 0 && segment !== "." && segment !== "..")
        .join("/");
}

/** Maps a file input's selection to upload items, preserving `webkitRelativePath` for folder uploads. */
export function itemsFromFileList(files: FileList | File[]): UploadItem[] {
    return Array.from(files).map((file) => {
        const relativePath = normalizeUploadPath(file.webkitRelativePath || file.name);
        return {file, relativePath: relativePath || file.name};
    });
}

/**
 * The multipart filename is how the master learns each file's destination (see
 * `FilesRoutes.uploadFrames`). Re-wrap only when the path differs so plain multi-select
 * uploads keep their original File instance.
 */
export function toUploadFile(item: UploadItem): File {
    if (item.relativePath === item.file.name) return item.file;
    return new File([item.file], item.relativePath, {
        type: item.file.type,
        lastModified: item.file.lastModified,
    });
}

/**
 * Walks a drop's entries, preserving folder structure. `webkitGetAsEntry()` is read
 * synchronously before the first await — the browser releases the drag data afterwards.
 *
 * Note: `readEntries` returns at most ~100 entries per call, so it must be called in a
 * loop until it yields an empty batch. Empty folders cannot be represented.
 */
export async function collectDroppedItems(dataTransfer: DataTransfer): Promise<UploadItem[]> {
    const entries: FileSystemEntry[] = [];
    const fallbackFiles: File[] = [];

    for (const item of Array.from(dataTransfer.items)) {
        if (item.kind !== "file") continue;
        const entry = item.webkitGetAsEntry?.() ?? null;
        if (entry) {
            entries.push(entry);
        } else {
            const file = item.getAsFile();
            if (file) fallbackFiles.push(file);
        }
    }

    if (entries.length === 0) {
        return fallbackFiles.map((file) => ({file, relativePath: file.name}));
    }

    const items: UploadItem[] = [];
    for (const entry of entries) {
        await walkEntry(entry, "", items);
    }
    return items;
}

async function walkEntry(entry: FileSystemEntry, prefix: string, out: UploadItem[]): Promise<void> {
    if (entry.isFile) {
        const file = await readFileEntry(entry as FileSystemFileEntry);
        out.push({
            file,
            relativePath: prefix ? `${prefix}/${file.name}` : file.name,
        });
        return;
    }
    if (!entry.isDirectory) return;

    const directory = entry as FileSystemDirectoryEntry;
    const directoryPrefix = prefix ? `${prefix}/${entry.name}` : entry.name;
    const reader = directory.createReader();

    // readEntries only returns one batch per call; loop until it reports the end.
    while (true) {
        const batch = await readEntryBatch(reader);
        if (batch.length === 0) break;
        for (const child of batch) {
            await walkEntry(child, directoryPrefix, out);
        }
    }
}

function readFileEntry(entry: FileSystemFileEntry): Promise<File> {
    return new Promise((resolve, reject) => entry.file(resolve, reject));
}

function readEntryBatch(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
    return new Promise((resolve, reject) => reader.readEntries(resolve, reject));
}
