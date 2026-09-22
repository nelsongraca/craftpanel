"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {usePromptDialog} from "@/lib/hooks/usePromptDialog";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {
    copyServerFile,
    deleteServerFile,
    downloadServerFile,
    listServerFiles,
    mkdirServerFile,
    moveServerFile,
    readServerFile,
    uploadServerFile,
    writeServerFile,
} from "@/lib/generated/sdk.gen";
import {
    ArrowLeft,
    ChevronDown,
    ChevronRight,
    Copy,
    Download,
    File,
    Folder,
    FolderPlus,
    MoreVertical,
    Move,
    Pencil,
    Save,
    Trash2,
    Upload,
    X,
    WrapText,
} from "lucide-react";
import {FileCodeEditor} from "@/components/servers/file-code-editor";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
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
}

function buildPath(parent: string, name: string): string {
    return parent === "/" ? `/${name}` : `${parent}/${name}`;
}

function parentDir(path: string): string {
    return path.substring(0, path.lastIndexOf("/")) || "/";
}

function copyDefaultPath(node: TreeNode): string {
    const dot = node.isDirectory ? -1 : node.name.lastIndexOf(".");
    const stem = dot > 0 ? node.name.slice(0, dot) : node.name;
    const ext = dot > 0 ? node.name.slice(dot) : "";
    return buildPath(parentDir(node.path), `${stem}-copy${ext}`);
}

export function FilesTab({serverId}: Props) {
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
    const {confirm, dialog} = useConfirmDialog();
    const {prompt, dialog: promptDialog} = usePromptDialog();
    const [renameNode, setRenameNode] = useState<{path: string; name: string} | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const [currentDir, setCurrentDir] = useState("/");
    const uploadRef = useRef<HTMLInputElement>(null);
    const pendingUploadDirRef = useRef<string | null>(null);

    const loadDir = useCallback(
        async (path: string): Promise<TreeNode[]> => {
            const {data, error: err} = await listServerFiles({path: {id: serverId}, query: {path}});
            if (err || !data) {
                setError((err as {message?: string})?.message ?? "Failed to list files - agent may be disconnected");
                return [];
            }
            return (data.entries ?? []).map((e) => ({
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
        prompt({
            title: "Move",
            description: node.path,
            label: "Destination path",
            defaultValue: node.path,
            confirmLabel: "Move",
            onConfirm: async (dest) => {
                if (dest === node.path) return;
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
        prompt({
            title: "Copy",
            description: node.path,
            label: "Destination path",
            defaultValue: copyDefaultPath(node),
            confirmLabel: "Copy",
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

    async function uploadFile(file: File, destPath: string) {
        const {error: err} = await uploadServerFile({
            path: {id: serverId},
            body: {path: destPath, file: file as unknown as number[]},
        });
        if (err) {
            setError("Upload failed");
            return;
        }
        setRoots(await loadDir("/"));
    }

    function uploadHere(dirPath: string) {
        pendingUploadDirRef.current = dirPath;
        uploadRef.current?.click();
    }

    function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
        const file = e.target.files?.[0];
        const pendingDir = pendingUploadDirRef.current;
        pendingUploadDirRef.current = null;
        if (e.target) e.target.value = "";
        if (!file) return;
        if (pendingDir !== null) {
            void uploadFile(file, buildPath(pendingDir, file.name));
            return;
        }
        prompt({
            title: "Upload File",
            description: `Uploading to ${currentDir}`,
            label: "Destination path",
            defaultValue: buildPath(currentDir, file.name),
            confirmLabel: "Upload",
            onConfirm: (destPath) => void uploadFile(file, destPath),
        });
    }

    function renderTree(nodes: TreeNode[], depth = 0): React.ReactNode {
        return nodes.map((node) => {
            const isSelected = selectedPath === node.path;
            const isCurrent = node.isDirectory && currentDir === node.path;
            return (
                <div key={node.path}>
                    <div
                        className={[
                            "group flex cursor-pointer items-center gap-1.5 rounded px-2 py-0.5 text-xs select-none",
                            isSelected
                                ? "bg-surface-higher text-text-primary"
                                : isCurrent
                                  ? "bg-surface-high text-accent"
                                  : "text-text-dim hover:bg-surface-high hover:text-text-primary",
                        ].join(" ")}
                        style={{paddingLeft: `${8 + depth * 14}px`}}
                        onClick={() => {
                            if (node.isDirectory) void toggleDir(node);
                            else void openFile(node);
                        }}
                    >
                        {node.isDirectory ? (
                            node.loading ? (
                                <span className="h-3 w-3 shrink-0 animate-spin rounded-full border border-text-muted border-t-accent" />
                            ) : node.expanded ? (
                                <ChevronDown size={12} className="shrink-0 text-text-muted" />
                            ) : (
                                <ChevronRight size={12} className="shrink-0 text-text-muted" />
                            )
                        ) : (
                            <span className="w-3" />
                        )}
                        {node.isDirectory ? (
                            <Folder size={13} className="shrink-0 text-accent" />
                        ) : (
                            <File size={13} className="shrink-0 text-text-muted" />
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
                                className="p-0.5 hover:text-accent"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    void startRename(node);
                                }}
                            >
                                <Pencil size={10} />
                            </button>
                            <button
                                title="Move"
                                className="p-0.5 hover:text-accent"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    moveEntry(node);
                                }}
                            >
                                <Move size={10} />
                            </button>
                            <button
                                title="Copy"
                                className="p-0.5 hover:text-accent"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    copyEntry(node);
                                }}
                            >
                                <Copy size={10} />
                            </button>
                            {node.isDirectory && (
                                <button
                                    title="Upload here"
                                    className="p-0.5 hover:text-accent"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        uploadHere(node.path);
                                    }}
                                >
                                    <Upload size={10} />
                                </button>
                            )}
                            {!node.isDirectory && (
                                <button
                                    title="Download"
                                    className="p-0.5 hover:text-accent"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        void handleDownload(node.path);
                                    }}
                                >
                                    <Download size={10} />
                                </button>
                            )}
                            <button
                                title="Delete"
                                className="p-0.5 hover:text-error"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    void deleteEntry(node.path, node.isDirectory);
                                }}
                            >
                                <Trash2 size={10} />
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
                                    <MoreVertical size={14} />
                                </DropdownMenuTrigger>
                                <DropdownMenuContent
                                    align="end"
                                    className="min-w-[160px] border-border bg-surface-higher"
                                    onClick={(e) => e.stopPropagation()}
                                >
                                    <DropdownMenuItem onClick={() => void startRename(node)}>
                                        <Pencil size={12} /> Rename
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onClick={() => moveEntry(node)}>
                                        <Move size={12} /> Move…
                                    </DropdownMenuItem>
                                    <DropdownMenuItem onClick={() => copyEntry(node)}>
                                        <Copy size={12} /> Copy…
                                    </DropdownMenuItem>
                                    {node.isDirectory && (
                                        <DropdownMenuItem onClick={() => uploadHere(node.path)}>
                                            <Upload size={12} /> Upload here
                                        </DropdownMenuItem>
                                    )}
                                    {!node.isDirectory && (
                                        <DropdownMenuItem onClick={() => void handleDownload(node.path)}>
                                            <Download size={12} /> Download
                                        </DropdownMenuItem>
                                    )}
                                    <DropdownMenuSeparator />
                                    <DropdownMenuItem
                                        variant="destructive"
                                        onClick={() => void deleteEntry(node.path, node.isDirectory)}
                                    >
                                        <Trash2 size={12} /> Delete
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
            <div className="flex h-full min-h-0">
                {/* ── Tree ── On mobile it is a full-screen list until a file is picked; on md+ it
                    is the fixed side pane and is always shown. */}
                <div
                    className={`${selectedPath ? "hidden md:flex" : "flex"} w-full shrink-0 flex-col overflow-hidden border-r border-border md:w-64`}
                >
                    <div className="flex items-center gap-1 border-b border-border px-3 py-2">
                        <span className="flex-1 font-heading text-xs font-bold tracking-wider text-text-muted uppercase">
                            Files
                        </span>
                        <button
                            title="Upload file"
                            className="p-1 text-text-muted hover:text-accent"
                            onClick={() => uploadRef.current?.click()}
                        >
                            <Upload size={13} />
                        </button>
                        <button
                            title="New folder"
                            className="p-1 text-text-muted hover:text-accent"
                            onClick={mkdirPrompt}
                        >
                            <FolderPlus size={13} />
                        </button>
                        <input ref={uploadRef} type="file" className="hidden" onChange={handleUpload} />
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
                            <div className="flex items-center gap-2 border-b border-border px-4 py-2">
                                <button
                                    title="Back to files"
                                    className="-ml-1 p-1 text-text-dim hover:text-accent md:hidden"
                                    onClick={() => setSelectedPath(null)}
                                >
                                    <ArrowLeft size={14} />
                                </button>
                                <span className="flex-1 truncate font-mono text-xs text-text-dim">{selectedPath}</span>
                                {fileEncoding !== "binary" && (
                                    <>
                                        <button
                                            title={wrap ? "Disable word wrap" : "Enable word wrap"}
                                            className={`p-1 text-text-muted transition-colors hover:text-accent ${wrap ? "text-accent" : ""}`}
                                            onClick={() => setWrap((w) => !w)}
                                        >
                                            <WrapText size={13} />
                                        </button>
                                        <button
                                            className="flex items-center gap-1 rounded bg-accent px-2.5 py-1 text-xs font-bold text-bg disabled:opacity-50"
                                            onClick={() => void saveFile()}
                                            disabled={savingFile || !dirty}
                                        >
                                            <Save size={11} />
                                            {savingFile ? "Saving…" : "Save"}
                                        </button>
                                    </>
                                )}
                                {fileEncoding === "binary" && (
                                    <button
                                        className="flex items-center gap-1 rounded border border-border bg-surface-higher px-2.5 py-1 text-xs font-bold text-text-primary"
                                        onClick={() => void handleDownload(selectedPath)}
                                    >
                                        <Download size={11} />
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
                                        value={fileContent}
                                        onChange={(val) => {
                                            setFileContent(val);
                                            setDirty(true);
                                        }}
                                        onSave={saveFile}
                                        path={selectedPath}
                                        encoding={fileEncoding}
                                        wrap={wrap}
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
        </>
    );
}
