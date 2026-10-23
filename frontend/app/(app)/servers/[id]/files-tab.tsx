"use client";

import {memo, useCallback, useEffect, useRef, useState} from "react";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {usePromptDialog} from "@/lib/hooks/usePromptDialog";
import {FULLSCREEN_PANE_CLASS, useFullscreenPane} from "@/lib/hooks/useFullscreenPane";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {
    copyServerFile,
    deleteServerFile,
    downloadServerFile,
    listServerFiles,
    mkdirServerFile,
    moveServerFile,
    readServerFile,
    writeServerFile,
} from "@/lib/generated/sdk.gen";
import {
    ArrowLeft,
    ChevronDown,
    ChevronRight,
    Code2,
    Copy,
    Download,
    File,
    Folder,
    FolderPlus,
    FolderUp,
    Maximize2,
    Minimize2,
    MoreVertical,
    Move,
    Pencil,
    Save,
    Search,
    Trash2,
    Upload,
    X,
    WrapText,
} from "lucide-react";
import {FileCodeEditor, type FileCodeEditorHandle} from "@/components/servers/file-code-editor";
import {DirectoryPickerDialog} from "@/components/servers/directory-picker-dialog";
import {UploadDialog} from "@/components/servers/upload-dialog";
import {collectDroppedItems, itemsFromFileList, type UploadItem} from "@/lib/upload-drop";
import {cn} from "@/lib/utils";
import {LANGUAGE_IDS, LANGUAGE_LABELS, languageIdFromPath, type LanguageId} from "@/lib/file-language";
import {
    loadLanguageOverrides,
    saveLanguageOverrides,
    setLanguageOverride,
    type LanguageOverrides,
} from "@/lib/file-language-overrides";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuRadioGroup,
    DropdownMenuRadioItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

interface FileEntry {
    name: string;
    isDirectory: boolean;
    sizeBytes: number;
    modifiedAt: string | null;
    permissions: string;
}

interface TreeNode extends FileEntry {
    path: string;
    children?: TreeNode[];
    expanded: boolean;
    loading: boolean;
}

interface Props {
    serverId: string;
    active?: boolean;
}

function buildPath(parent: string, name: string): string {
    return parent === "/" ? `/${name}` : `${parent}/${name}`;
}

function parentDir(path: string): string {
    return path.substring(0, path.lastIndexOf("/")) || "/";
}

function isFileDrag(event: React.DragEvent): boolean {
    return Array.from(event.dataTransfer?.types ?? []).includes("Files");
}

function isSameTarget(event: React.DragEvent): boolean {
    const related = event.relatedTarget;
    return related instanceof Node && event.currentTarget.contains(related);
}

interface PickerState {
    title: string;
    description: string;
    confirmLabel: string;
    initialDir: string;
    initialName: string;
    validate?: (destinationPath: string) => string | null;
    onConfirm: (destinationPath: string) => void;
}

function copyDefaultName(node: TreeNode): string {
    const dot = node.isDirectory ? -1 : node.name.lastIndexOf(".");
    const stem = dot > 0 ? node.name.slice(0, dot) : node.name;
    const ext = dot > 0 ? node.name.slice(dot) : "";
    return `${stem}-copy${ext}`;
}

/**
 * Memoised so the parent's live-metrics WebSocket ticks (every few seconds while a server runs)
 * do not re-render the whole file manager — and, more importantly, do not re-render the editor
 * subtree, which reconfigures CodeMirror underneath the find panel.
 */
export const FilesTab = memo(function FilesTab({serverId, active = true}: Props) {
    const {fullscreen, toggle: toggleFullscreen} = useFullscreenPane(active);
    const [roots, setRoots] = useState<TreeNode[]>([]);
    const [selectedPath, setSelectedPath] = useState<string | null>(null);
    const [fileContent, setFileContent] = useState<string>("");
    const [fileEncoding, setFileEncoding] = useState<string>("utf-8");
    const [loadingFile, setLoadingFile] = useState(false);
    const [savingFile, setSavingFile] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [rootLoading, setRootLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [wrap, setWrap] = useState(false);
    const editorRef = useRef<FileCodeEditorHandle>(null);
    const [languageOverrides, setLanguageOverrides] = useState<LanguageOverrides>({});
    const {confirm, dialog} = useConfirmDialog();
    const {prompt, dialog: promptDialog} = usePromptDialog();
    const [renameNode, setRenameNode] = useState<{path: string; name: string} | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const [picker, setPicker] = useState<PickerState | null>(null);
    const [currentDir, setCurrentDir] = useState("/");
    const fileInputRef = useRef<HTMLInputElement>(null);
    const folderInputRef = useRef<HTMLInputElement>(null);
    const pendingUploadDirRef = useRef<string | null>(null);
    const [preparingUpload, setPreparingUpload] = useState(false);
    const [dragTarget, setDragTarget] = useState<string | null>(null);
    const [upload, setUpload] = useState<{
        items: UploadItem[];
        destDir: string;
        conflicts: string[];
        requiresConfirmation: boolean;
    } | null>(null);

    const loadDir = useCallback(
        async (path: string): Promise<TreeNode[]> => {
            const {data, error: err} = await listServerFiles({path: {id: serverId}, query: {path}});
            if (err || !data) {
                setError((err as {message?: string})?.message ?? "Failed to list files - agent may be disconnected");
                return [];
            }
            return (data.entries ?? [])
                .slice()
                .sort((a, b) =>
                    a.is_directory === b.is_directory
                        ? a.name.localeCompare(b.name, undefined, {numeric: true, sensitivity: "base"})
                        : a.is_directory
                          ? -1
                          : 1,
                )
                .map((e) => ({
                    name: e.name,
                    isDirectory: e.is_directory,
                    sizeBytes: e.size_bytes ?? 0,
                    modifiedAt: e.modified_at ?? null,
                    permissions: e.permissions ?? "",
                    path: buildPath(path, e.name),
                    expanded: false,
                    loading: false,
                }));
        },
        [serverId],
    );

    useEffect(() => {
        setRootLoading(true);
        loadDir("/").then((nodes) => {
            setRoots(nodes);
            setRootLoading(false);
        });
    }, [loadDir]);

    useEffect(() => {
        setLanguageOverrides(loadLanguageOverrides());
    }, []);

    function changeLanguage(lang: LanguageId | null) {
        if (!selectedPath) return;
        const next = setLanguageOverride(languageOverrides, selectedPath, lang);
        setLanguageOverrides(next);
        saveLanguageOverrides(next);
    }

    const selectedLanguageOverride = selectedPath ? languageOverrides[selectedPath] : undefined;

    function updateNode(
        nodes: TreeNode[],
        path: string,
        update: Partial<TreeNode> & {children?: TreeNode[]},
    ): TreeNode[] {
        return nodes.map((n) => {
            if (n.path === path) return {...n, ...update};
            if (n.children) return {...n, children: updateNode(n.children, path, update)};
            return n;
        });
    }

    async function toggleDir(node: TreeNode) {
        if (!node.isDirectory) return;
        setCurrentDir(node.path);
        if (!node.expanded && !node.children) {
            setRoots((prev) => updateNode(prev, node.path, {loading: true, expanded: true}));
            const children = await loadDir(node.path);
            setRoots((prev) => updateNode(prev, node.path, {loading: false, children, expanded: true}));
        } else {
            setRoots((prev) => updateNode(prev, node.path, {expanded: !node.expanded}));
        }
    }

    async function openFile(node: TreeNode) {
        if (node.isDirectory) {
            void toggleDir(node);
            return;
        }
        setSelectedPath(node.path);
        setLoadingFile(true);
        setDirty(false);
        setError(null);
        setFileContent("");
        setFileEncoding("utf-8");
        const ext = node.path.split(".").pop()?.toLowerCase();
        setWrap(ext === "md" || ext === "markdown" || ext === "log" || ext === "txt");
        const {data, error: err} = await readServerFile({path: {id: serverId}, query: {path: node.path}});
        setLoadingFile(false);
        if (err || !data) {
            setError("Failed to load file");
            return;
        }
        setFileEncoding(data.encoding ?? "utf-8");
        setFileContent(data.content ?? "");
    }

    async function saveFile() {
        if (!selectedPath || fileEncoding === "binary") return;
        setSavingFile(true);
        setError(null);
        const {error: err} = await writeServerFile({
            path: {id: serverId},
            query: {path: selectedPath},
            body: fileContent,
        });
        setSavingFile(false);
        if (err) {
            setError("Failed to save file");
            return;
        }
        setDirty(false);
    }

    function deleteEntry(path: string, isDir: boolean) {
        confirm({
            title: "Delete File?",
            description: `Delete ${path}? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                const {error: err} = await deleteServerFile({
                    path: {id: serverId},
                    query: {path, recursive: isDir ? true : undefined},
                });
                if (err) {
                    setError("Failed to delete");
                    return;
                }
                if (selectedPath === path) {
                    setSelectedPath(null);
                    setFileContent("");
                }
                setRoots(await loadDir("/"));
            },
        });
    }

    function mkdirPrompt() {
        prompt({
            title: "New Folder",
            label: "Path (relative to /)",
            confirmLabel: "Create",
            onConfirm: async (name) => {
                const {error: err} = await mkdirServerFile({
                    path: {id: serverId},
                    body: {path: name.startsWith("/") ? name : `/${name}`},
                });
                if (err) {
                    setError("Failed to create directory");
                    return;
                }
                setRoots(await loadDir("/"));
            },
        });
    }

    async function startRename(node: TreeNode) {
        setRenameNode({path: node.path, name: node.name});
        setRenameValue(node.name);
    }

    async function commitRename() {
        if (!renameNode) return;
        const dest = buildPath(parentDir(renameNode.path), renameValue);
        if (dest === renameNode.path) {
            setRenameNode(null);
            return;
        }
        const {error: err} = await moveServerFile({
            path: {id: serverId},
            body: {source_path: renameNode.path, destination_path: dest},
        });
        setRenameNode(null);
        if (err) {
            setError("Failed to rename");
            return;
        }
        setRoots(await loadDir("/"));
    }

    function moveEntry(node: TreeNode) {
        setPicker({
            title: `Move "${node.name}"`,
            description: node.path,
            confirmLabel: "Move",
            initialDir: parentDir(node.path),
            initialName: node.name,
            validate: (dest) => {
                if (dest === node.path) return "Choose a different location";
                if (node.isDirectory && dest.startsWith(`${node.path}/`)) return "A folder cannot be moved into itself";
                return null;
            },
            onConfirm: async (dest) => {
                const {error: err} = await moveServerFile({
                    path: {id: serverId},
                    body: {source_path: node.path, destination_path: dest},
                });
                if (err) {
                    setError("Failed to move");
                    return;
                }
                setRoots(await loadDir("/"));
            },
        });
    }

    function copyEntry(node: TreeNode) {
        setPicker({
            title: `Copy "${node.name}"`,
            description: node.path,
            confirmLabel: "Copy",
            initialDir: parentDir(node.path),
            initialName: copyDefaultName(node),
            validate: (dest) => {
                if (dest === node.path) return "Destination matches the source";
                if (node.isDirectory && dest.startsWith(`${node.path}/`))
                    return "A folder cannot be copied into itself";
                return null;
            },
            onConfirm: async (dest) => {
                const {error: err} = await copyServerFile({
                    path: {id: serverId},
                    body: {source_path: node.path, destination_path: dest, recursive: node.isDirectory},
                });
                if (err) {
                    setError("Failed to copy");
                    return;
                }
                setRoots(await loadDir("/"));
            },
        });
    }

    async function handleDownload(path: string) {
        setError(null);
        const {data, error: err} = await downloadServerFile({
            path: {id: serverId},
            query: {path},
        });
        if (err || !data) {
            setError("Failed to download file");
            return;
        }
        const blob = data as Blob;
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = path.split("/").filter(Boolean).pop() ?? "download";
        a.click();
        URL.revokeObjectURL(url);
    }

    // React has no typed `webkitdirectory` prop; set it imperatively on the hidden input.
    useEffect(() => {
        folderInputRef.current?.setAttribute("webkitdirectory", "");
    }, []);

    async function findConflicts(items: UploadItem[], destDir: string): Promise<string[]> {
        const byDirectory = new Map<string, UploadItem[]>();
        for (const item of items) {
            const directory = parentDir(buildPath(destDir, item.relativePath));
            const bucket = byDirectory.get(directory);
            if (bucket) bucket.push(item);
            else byDirectory.set(directory, [item]);
        }

        const conflicts = new Set<string>();
        const directories = [...byDirectory.keys()];
        let next = 0;

        async function checkNext() {
            while (next < directories.length) {
                const directory = directories[next++];
                const {data} = await listServerFiles({path: {id: serverId}, query: {path: directory}});
                if (!data) continue; // A missing destination directory has nothing to overwrite.
                const existing = new Set((data.entries ?? []).map((entry) => entry.name));
                for (const item of byDirectory.get(directory) ?? []) {
                    if (existing.has(item.file.name)) {
                        conflicts.add(buildPath(destDir, item.relativePath));
                    }
                }
            }
        }

        // Listing directories is independent; a handful in flight keeps large folder uploads quick.
        await Promise.all(Array.from({length: Math.min(4, directories.length)}, () => checkNext()));
        return [...conflicts];
    }

    async function beginUpload(items: UploadItem[], destDir: string) {
        if (items.length === 0) return;
        setError(null);
        setPreparingUpload(true);
        let conflicts: string[] = [];
        try {
            conflicts = await findConflicts(items, destDir);
        } catch {
            // A failed pre-check must not block the upload; the agent still validates every path.
        } finally {
            setPreparingUpload(false);
        }
        setUpload({
            items,
            destDir,
            conflicts,
            // A single new file goes straight up; folders, multi-select and overwrites ask first.
            requiresConfirmation:
                conflicts.length > 0 || items.length > 1 || items.some((item) => item.relativePath.includes("/")),
        });
    }

    function handleUploadSelection(e: React.ChangeEvent<HTMLInputElement>) {
        const items = itemsFromFileList(e.target.files ?? []);
        const destination = pendingUploadDirRef.current ?? currentDir;
        pendingUploadDirRef.current = null;
        if (e.target) e.target.value = "";
        void beginUpload(items, destination);
    }

    function uploadHere(dirPath: string) {
        pendingUploadDirRef.current = dirPath;
        fileInputRef.current?.click();
    }

    async function handleDrop(e: React.DragEvent, destDir: string) {
        e.preventDefault();
        e.stopPropagation();
        setDragTarget(null);
        // webkitGetAsEntry must be read synchronously from the drop handler; collectDroppedItems
        // captures the entries before its first await.
        const items = await collectDroppedItems(e.dataTransfer);
        await beginUpload(items, destDir);
    }

    function renderTree(nodes: TreeNode[], depth = 0): React.ReactNode {
        return nodes.map((node) => {
            const isSelected = selectedPath === node.path;
            const isCurrent = node.isDirectory && currentDir === node.path;
            const dropDir = node.isDirectory ? node.path : parentDir(node.path);
            return (
                <div key={node.path}>
                    <div
                        className={[
                            "group flex cursor-pointer items-center gap-1.5 rounded py-1 pr-2 text-sm select-none",
                            isSelected
                                ? "bg-surface-higher text-text-primary"
                                : isCurrent
                                  ? "bg-surface-high text-accent"
                                  : "text-text-dim hover:bg-surface-high hover:text-text-primary",
                            dragTarget === dropDir && "bg-accent/10 text-text-primary ring-2 ring-accent ring-inset",
                        ].join(" ")}
                        style={{paddingLeft: `${12 + depth * 16}px`}}
                        onClick={() => {
                            if (node.isDirectory) void toggleDir(node);
                            else void openFile(node);
                        }}
                        onDragOver={(e) => {
                            if (!isFileDrag(e)) return;
                            e.preventDefault();
                            e.stopPropagation();
                            e.dataTransfer.dropEffect = "copy";
                            setDragTarget(dropDir);
                        }}
                        onDragLeave={(e) => {
                            if (isFileDrag(e) && !isSameTarget(e)) setDragTarget(null);
                        }}
                        onDrop={(e) => {
                            if (!isFileDrag(e)) return;
                            void handleDrop(e, dropDir);
                        }}
                    >
                        {node.isDirectory ? (
                            node.loading ? (
                                <span className="h-3 w-3 shrink-0 animate-spin rounded-full border border-text-muted border-t-accent" />
                            ) : node.expanded ? (
                                <ChevronDown size={14} className="shrink-0 text-text-muted" />
                            ) : (
                                <ChevronRight size={14} className="shrink-0 text-text-muted" />
                            )
                        ) : (
                            <span className="w-3" />
                        )}
                        {node.isDirectory ? (
                            <Folder size={15} className="shrink-0 text-accent" />
                        ) : (
                            <File size={15} className="shrink-0 text-text-muted" />
                        )}
                        {renameNode?.path === node.path ? (
                            <input
                                autoFocus
                                value={renameValue}
                                onChange={(e) => setRenameValue(e.target.value)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") void commitRename();
                                    if (e.key === "Escape") setRenameNode(null);
                                }}
                                onBlur={() => void commitRename()}
                                className="flex-1 rounded border border-accent bg-bg px-1 font-mono text-xs outline-none"
                                onClick={(e) => e.stopPropagation()}
                            />
                        ) : (
                            <span className="flex-1 truncate font-mono">{node.name}</span>
                        )}

                        {/* ── Desktop: inline actions, revealed on hover ── */}
                        <span className="hidden shrink-0 items-center gap-0.5 md:group-hover:flex">
                            <button
                                title="Rename"
                                aria-label={`Rename ${node.name}`}
                                className="p-1 hover:text-accent"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    void startRename(node);
                                }}
                            >
                                <Pencil size={13} />
                            </button>
                            <button
                                title="Move"
                                aria-label={`Move ${node.name}`}
                                className="p-1 hover:text-accent"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    moveEntry(node);
                                }}
                            >
                                <Move size={13} />
                            </button>
                            <button
                                title="Copy"
                                aria-label={`Copy ${node.name}`}
                                className="p-1 hover:text-accent"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    copyEntry(node);
                                }}
                            >
                                <Copy size={13} />
                            </button>
                            {node.isDirectory && (
                                <button
                                    title="Upload here"
                                    aria-label={`Upload to ${node.name}`}
                                    className="p-1 hover:text-accent"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        uploadHere(node.path);
                                    }}
                                >
                                    <Upload size={13} />
                                </button>
                            )}
                            {!node.isDirectory && (
                                <button
                                    title="Download"
                                    aria-label={`Download ${node.name}`}
                                    className="p-1 hover:text-accent"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        void handleDownload(node.path);
                                    }}
                                >
                                    <Download size={13} />
                                </button>
                            )}
                            <button
                                title="Delete"
                                aria-label={`Delete ${node.name}`}
                                className="p-1 hover:text-error"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    void deleteEntry(node.path, node.isDirectory);
                                }}
                            >
                                <Trash2 size={13} />
                            </button>
                        </span>

                        {/* ── Mobile: overflow menu (touch has no hover) ── */}
                        <span className="flex shrink-0 items-center md:hidden">
                            <DropdownMenu>
                                <DropdownMenuTrigger
                                    title="Actions"
                                    className="p-1 text-text-muted hover:text-accent"
                                    onClick={(e) => e.stopPropagation()}
                                >
                                    <MoreVertical size={16} />
                                </DropdownMenuTrigger>
                                <DropdownMenuContent
                                    align="end"
                                    className="min-w-[160px] border-border bg-surface-higher"
                                    onClick={(e) => e.stopPropagation()}
                                >
                                    <DropdownMenuItem onClick={() => void startRename(node)}>
                                        <Pencil size={14} /> Rename
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onClick={() => moveEntry(node)}>
                                        <Move size={14} /> Move…
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onClick={() => copyEntry(node)}>
                                        <Copy size={14} /> Copy…
                                    </DropdownMenuItem>
                                    {node.isDirectory && (
                                        <DropdownMenuItem onClick={() => uploadHere(node.path)}>
                                            <Upload size={14} /> Upload here
                                        </DropdownMenuItem>
                                    )}
                                    {!node.isDirectory && (
                                        <DropdownMenuItem onClick={() => void handleDownload(node.path)}>
                                            <Download size={14} /> Download
                                        </DropdownMenuItem>
                                    )}
                                    <DropdownMenuSeparator />
                                    <DropdownMenuItem
                                        variant="destructive"
                                        onClick={() => void deleteEntry(node.path, node.isDirectory)}
                                    >
                                        <Trash2 size={14} /> Delete
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </span>
                    </div>
                    {node.isDirectory && node.expanded && node.children && (
                        <div>{renderTree(node.children, depth + 1)}</div>
                    )}
                </div>
            );
        });
    }

    return (
        <>
            <div className={cn("flex h-full min-h-0", fullscreen ? `${FULLSCREEN_PANE_CLASS} gap-0` : "gap-4 p-4")}>
                {/* ── Tree ── On mobile it is a full-screen list until a file is picked; on md+ it
                        is the fixed side pane and is always shown. */}
                <div
                    className={`${selectedPath ? "hidden md:flex" : "flex"} relative w-full shrink-0 flex-col overflow-hidden rounded-md border border-border bg-surface md:w-64`}
                    onDragOver={(e) => {
                        if (!isFileDrag(e)) return;
                        e.preventDefault();
                        e.dataTransfer.dropEffect = "copy";
                        setDragTarget(currentDir);
                    }}
                    onDragLeave={(e) => {
                        if (isFileDrag(e) && !isSameTarget(e)) setDragTarget(null);
                    }}
                    onDrop={(e) => {
                        if (!isFileDrag(e)) return;
                        void handleDrop(e, currentDir);
                    }}
                >
                    {dragTarget !== null && (
                        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center border-2 border-dashed border-accent bg-accent/10 p-3">
                            <span className="rounded bg-surface px-2 py-1 text-center font-mono text-xs text-accent">
                                Drop to upload to {dragTarget}
                            </span>
                        </div>
                    )}
                    <div className="flex items-center gap-1 border-b border-border px-3 py-2">
                        <span className="flex-1 font-heading text-xs font-bold tracking-wider text-text-muted uppercase">
                            Files
                        </span>
                        <button
                            type="button"
                            title={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                            aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                            className="p-1 text-text-muted hover:text-accent md:hidden"
                            onClick={toggleFullscreen}
                        >
                            {fullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
                        </button>
                        {preparingUpload ? (
                            <span
                                title="Preparing upload"
                                aria-label="Preparing upload"
                                className="my-0.5 h-4 w-4 animate-spin rounded-full border border-text-muted border-t-accent"
                            />
                        ) : (
                            <>
                                <button
                                    title="Upload files"
                                    aria-label="Upload files"
                                    className="p-1 text-text-muted hover:text-accent"
                                    onClick={() => {
                                        pendingUploadDirRef.current = null;
                                        fileInputRef.current?.click();
                                    }}
                                >
                                    <Upload size={16} />
                                </button>
                                <button
                                    title="Upload folder"
                                    aria-label="Upload folder"
                                    className="p-1 text-text-muted hover:text-accent"
                                    onClick={() => {
                                        pendingUploadDirRef.current = null;
                                        folderInputRef.current?.click();
                                    }}
                                >
                                    <FolderUp size={16} />
                                </button>
                            </>
                        )}
                        <button
                            title="New folder"
                            className="p-1 text-text-muted hover:text-accent"
                            onClick={mkdirPrompt}
                        >
                            <FolderPlus size={16} />
                        </button>
                        <input
                            ref={fileInputRef}
                            type="file"
                            multiple
                            className="hidden"
                            onChange={handleUploadSelection}
                        />
                        <input
                            ref={folderInputRef}
                            type="file"
                            multiple
                            className="hidden"
                            onChange={handleUploadSelection}
                        />
                    </div>
                    <div className="flex-1 overflow-y-auto py-1">
                        {rootLoading ? (
                            <p className="px-3 py-2 text-xs text-text-muted">Loading…</p>
                        ) : roots.length === 0 && !error ? (
                            <p className="px-3 py-2 text-xs text-text-muted">Empty directory</p>
                        ) : (
                            renderTree(roots)
                        )}
                    </div>
                </div>

                {/* ── Editor ── */}
                <div className={`${selectedPath ? "flex" : "hidden md:flex"} min-h-0 flex-1 flex-col overflow-hidden`}>
                    {error && (
                        <div className="flex items-center gap-2 border-b border-error/20 bg-error/10 px-4 py-1.5 font-mono text-xs text-error">
                            <X size={12} />
                            {error}
                        </div>
                    )}

                    {selectedPath ? (
                        <>
                            <div className="flex items-center gap-1 border-b border-border px-4 py-2 sm:gap-2">
                                <button
                                    title="Back to files"
                                    className="-ml-1 p-1.5 text-text-dim hover:text-accent md:hidden"
                                    onClick={() => setSelectedPath(null)}
                                >
                                    <ArrowLeft size={16} />
                                </button>
                                <span className="flex-1 truncate font-mono text-sm text-text-dim">{selectedPath}</span>
                                <button
                                    type="button"
                                    title={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                                    aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                                    className="p-1.5 text-text-muted transition-colors hover:text-accent md:hidden"
                                    onClick={toggleFullscreen}
                                >
                                    {fullscreen ? <Minimize2 size={15} /> : <Maximize2 size={15} />}
                                </button>
                                {fileEncoding !== "binary" && (
                                    <>
                                        <DropdownMenu>
                                            <DropdownMenuTrigger
                                                title="Syntax highlighting"
                                                aria-label="Syntax highlighting"
                                                className="flex items-center gap-1 rounded px-1.5 py-1 text-xs text-text-muted transition-colors hover:text-accent"
                                            >
                                                <Code2 size={15} />
                                                <span className="hidden max-w-24 truncate sm:inline">
                                                    {selectedLanguageOverride
                                                        ? LANGUAGE_LABELS[selectedLanguageOverride]
                                                        : `Auto · ${LANGUAGE_LABELS[languageIdFromPath(selectedPath)]}`}
                                                </span>
                                                <ChevronDown size={12} />
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent
                                                align="end"
                                                className="min-w-[180px] border-border bg-surface-higher"
                                            >
                                                <DropdownMenuRadioGroup
                                                    value={selectedLanguageOverride ?? "auto"}
                                                    onValueChange={(value: string | null) =>
                                                        changeLanguage(value === "auto" ? null : (value as LanguageId))
                                                    }
                                                >
                                                    <DropdownMenuRadioItem value="auto" closeOnClick>
                                                        Auto ({LANGUAGE_LABELS[languageIdFromPath(selectedPath)]})
                                                    </DropdownMenuRadioItem>
                                                    <DropdownMenuSeparator />
                                                    {LANGUAGE_IDS.map((id) => (
                                                        <DropdownMenuRadioItem key={id} value={id} closeOnClick>
                                                            {LANGUAGE_LABELS[id]}
                                                        </DropdownMenuRadioItem>
                                                    ))}
                                                </DropdownMenuRadioGroup>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                        <button
                                            type="button"
                                            title="Find in file"
                                            aria-label="Find in file"
                                            className="p-1.5 text-text-muted transition-colors hover:text-accent"
                                            onClick={() => editorRef.current?.openSearch()}
                                        >
                                            <Search size={15} />
                                        </button>
                                        <button
                                            type="button"
                                            title={wrap ? "Disable word wrap" : "Enable word wrap"}
                                            aria-label={wrap ? "Disable word wrap" : "Enable word wrap"}
                                            className={`p-1.5 text-text-muted transition-colors hover:text-accent ${wrap ? "text-accent" : ""}`}
                                            onClick={() => setWrap((w) => !w)}
                                        >
                                            <WrapText size={15} />
                                        </button>
                                        <button
                                            className="flex items-center gap-1.5 rounded bg-accent px-3 py-1.5 text-sm font-bold text-on-accent disabled:opacity-50"
                                            onClick={() => void saveFile()}
                                            disabled={savingFile || !dirty}
                                        >
                                            <Save size={14} />
                                            {savingFile ? "Saving…" : "Save"}
                                        </button>
                                    </>
                                )}
                                {fileEncoding === "binary" && (
                                    <button
                                        className="flex items-center gap-1.5 rounded border border-border bg-surface-higher px-3 py-1.5 text-sm font-bold text-text-primary"
                                        onClick={() => void handleDownload(selectedPath)}
                                    >
                                        <Download size={14} />
                                        Download
                                    </button>
                                )}
                            </div>
                            <div className="min-h-0 flex-1">
                                {loadingFile ? (
                                    <p className="p-4 text-xs text-text-muted">Loading…</p>
                                ) : fileEncoding === "binary" ? (
                                    <p className="p-4 text-xs text-text-muted">
                                        Binary file - use the download button to retrieve it.
                                    </p>
                                ) : (
                                    <FileCodeEditor
                                        ref={editorRef}
                                        value={fileContent}
                                        onChange={(val) => {
                                            setFileContent(val);
                                            setDirty(true);
                                        }}
                                        onSave={saveFile}
                                        path={selectedPath}
                                        wrap={wrap}
                                        languageOverride={selectedLanguageOverride}
                                    />
                                )}
                            </div>
                        </>
                    ) : (
                        <Empty className="flex-1">
                            <EmptyDescription>Select a file to edit</EmptyDescription>
                        </Empty>
                    )}
                </div>
            </div>
            {dialog}
            {promptDialog}
            {picker && (
                <DirectoryPickerDialog
                    open
                    onOpenChange={(open) => !open && setPicker(null)}
                    title={picker.title}
                    description={picker.description}
                    confirmLabel={picker.confirmLabel}
                    initialDir={picker.initialDir}
                    initialName={picker.initialName}
                    loadDir={loadDir}
                    validate={picker.validate}
                    onConfirm={picker.onConfirm}
                />
            )}
            {upload && (
                <UploadDialog
                    open
                    onOpenChange={(open) => {
                        if (!open) setUpload(null);
                    }}
                    serverId={serverId}
                    destination={upload.destDir}
                    items={upload.items}
                    conflicts={upload.conflicts}
                    requiresConfirmation={upload.requiresConfirmation}
                    onFinished={() => {
                        void loadDir("/").then(setRoots);
                    }}
                />
            )}
        </>
    );
});
