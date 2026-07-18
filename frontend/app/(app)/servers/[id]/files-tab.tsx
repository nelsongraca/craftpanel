"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {deleteServerFile, listServerFiles, mkdirServerFile, moveServerFile, readServerFile,} from "@/lib/generated/sdk.gen";
import {ChevronDown, ChevronRight, Download, File, Folder, FolderPlus, Pencil, Save, Trash2, Upload, X} from "lucide-react";

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
    const {confirm, dialog} = useConfirmDialog();
    const [renameNode, setRenameNode] = useState<{ path: string; name: string } | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const uploadRef = useRef<HTMLInputElement>(null);

    const loadDir = useCallback(async (path: string): Promise<TreeNode[]> => {
        const {data, error: err} = await listServerFiles({path: {id: serverId}, query: {path}});
        if (err || !data) {
            setError((err as { message?: string })?.message ?? "Failed to list files - agent may be disconnected");
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
    }, [serverId]);

    useEffect(() => {
        setRootLoading(true);
        loadDir("/").then((nodes) => {
            setRoots(nodes);
            setRootLoading(false);
        });
    }, [loadDir]);

    function updateNode(nodes: TreeNode[], path: string, update: Partial<TreeNode> & { children?: TreeNode[] }): TreeNode[] {
        return nodes.map((n) => {
            if (n.path === path) return {...n, ...update};
            if (n.children) return {...n, children: updateNode(n.children, path, update)};
            return n;
        });
    }

    async function toggleDir(node: TreeNode) {
        if (!node.isDirectory) return;
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
        const res = await fetch(
            `/api/servers/${serverId}/files/content?path=${encodeURIComponent(selectedPath)}`,
            {method: "PUT", body: fileContent, headers: {"Content-Type": "text/plain"}},
        );
        setSavingFile(false);
        if (!res.ok) {
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

    async function mkdirPrompt() {
        const name = prompt("New folder path (relative to /):");
        if (!name) return;
        const {error: err} = await mkdirServerFile({path: {id: serverId}, body: {path: name.startsWith("/") ? name : `/${name}`}});
        if (err) {
            setError("Failed to create directory");
            return;
        }
        setRoots(await loadDir("/"));
    }

    async function startRename(node: TreeNode) {
        setRenameNode({path: node.path, name: node.name});
        setRenameValue(node.name);
    }

    async function commitRename() {
        if (!renameNode) return;
        const dir = renameNode.path.substring(0, renameNode.path.lastIndexOf("/")) || "/";
        const dest = buildPath(dir, renameValue);
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

    async function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
        const file = e.target.files?.[0];
        if (!file) return;
        const name = prompt("Destination path:", `/${file.name}`) ?? `/${file.name}`;
        const form = new FormData();
        form.append("path", name);
        form.append("file", file);
        const res = await fetch(`/api/servers/${serverId}/files/upload`, {method: "POST", body: form});
        if (!res.ok) {
            setError("Upload failed");
            return;
        }
        setRoots(await loadDir("/"));
        if (e.target) e.target.value = "";
    }

    function renderTree(nodes: TreeNode[], depth = 0): React.ReactNode {
        return nodes.map((node) => (
            <div key={node.path}>
                <div
                    className={[
                        "flex items-center gap-1.5 px-2 py-0.5 cursor-pointer text-xs rounded select-none group",
                        selectedPath === node.path ? "bg-surface-higher text-text-primary" : "text-text-dim hover:text-text-primary hover:bg-surface-high",
                    ].join(" ")}
                    style={{paddingLeft: `${8 + depth * 14}px`}}
                    onClick={() => {
                        if (node.isDirectory) void toggleDir(node); else void openFile(node);
                    }}
                >
                    {node.isDirectory ? (
                        node.loading
                            ? <span className="w-3 h-3 border border-text-muted border-t-accent rounded-full animate-spin shrink-0"/>
                            : node.expanded
                                ? <ChevronDown size={12} className="shrink-0 text-text-muted"/>
                                : <ChevronRight size={12} className="shrink-0 text-text-muted"/>
                    ) : (
                        <span className="w-3"/>
                    )}
                    {node.isDirectory
                        ? <Folder size={13} className="shrink-0 text-accent"/>
                        : <File size={13} className="shrink-0 text-text-muted"/>}
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
                            className="flex-1 bg-bg border border-accent rounded px-1 text-xs font-mono outline-none"
                            onClick={(e) => e.stopPropagation()}
                        />
                    ) : (
                        <span className="flex-1 truncate font-mono">{node.name}</span>
                    )}
                    <span className="hidden group-hover:flex items-center gap-0.5 shrink-0">
            <button
                title="Rename"
                className="p-0.5 hover:text-accent"
                onClick={(e) => {
                    e.stopPropagation();
                    void startRename(node);
                }}
            >
              <Pencil size={10}/>
            </button>
                        {!node.isDirectory && (
                            <a
                                title="Download"
                                href={`/api/servers/${serverId}/files/download?path=${encodeURIComponent(node.path)}`}
                                download
                                className="p-0.5 hover:text-accent"
                                onClick={(e) => e.stopPropagation()}
                            >
                                <Download size={10}/>
                            </a>
                        )}
                        <button
                            title="Delete"
                            className="p-0.5 hover:text-error"
                            onClick={(e) => {
                                e.stopPropagation();
                                void deleteEntry(node.path, node.isDirectory);
                            }}
                        >
              <Trash2 size={10}/>
            </button>
          </span>
                </div>
                {node.isDirectory && node.expanded && node.children && (
                    <div>{renderTree(node.children, depth + 1)}</div>
                )}
            </div>
        ));
    }

    return (
        <>
        <div className="flex h-[600px]">
            {/* ── Tree ── */}
            <div className="w-64 shrink-0 border-r border-border flex flex-col overflow-hidden">
                <div className="flex items-center gap-1 px-3 py-2 border-b border-border">
                    <span className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted flex-1">Files</span>
                    <button title="Upload file" className="p-1 text-text-muted hover:text-accent" onClick={() => uploadRef.current?.click()}>
                        <Upload size={13}/>
                    </button>
                    <button title="New folder" className="p-1 text-text-muted hover:text-accent" onClick={() => void mkdirPrompt()}>
                        <FolderPlus size={13}/>
                    </button>
                    <input ref={uploadRef} type="file" className="hidden" onChange={(e) => void handleUpload(e)}/>
                </div>
                <div className="flex-1 overflow-y-auto py-1">
                    {rootLoading ? (
                        <p className="text-text-muted text-xs px-3 py-2">Loading…</p>
                    ) : roots.length === 0 && !error ? (
                        <p className="text-text-muted text-xs px-3 py-2">Empty directory</p>
                    ) : (
                        renderTree(roots)
                    )}
                </div>
            </div>

            {/* ── Editor ── */}
            <div className="flex-1 flex flex-col overflow-hidden">
                {error && (
                    <div className="px-4 py-1.5 bg-error/10 border-b border-error/20 text-error text-xs font-mono flex items-center gap-2">
                        <X size={12}/>
                        {error}
                    </div>
                )}

                {selectedPath ? (
                    <>
                        <div className="flex items-center gap-2 px-4 py-2 border-b border-border">
                            <span className="font-mono text-xs text-text-dim flex-1 truncate">{selectedPath}</span>
                            {fileEncoding !== "binary" && (
                                <button
                                    className="flex items-center gap-1 px-2.5 py-1 bg-accent text-bg text-xs font-bold rounded disabled:opacity-50"
                                    onClick={() => void saveFile()}
                                    disabled={savingFile || !dirty}
                                >
                                    <Save size={11}/>
                                    {savingFile ? "Saving…" : "Save"}
                                </button>
                            )}
                            {fileEncoding === "binary" && (
                                <a
                                    href={`/api/servers/${serverId}/files/download?path=${encodeURIComponent(selectedPath)}`}
                                    download
                                    className="flex items-center gap-1 px-2.5 py-1 bg-surface-higher text-text-primary text-xs font-bold rounded border border-border"
                                >
                                    <Download size={11}/>
                                    Download
                                </a>
                            )}
                        </div>
                        <div className="flex-1 overflow-auto">
                            {loadingFile ? (
                                <p className="text-text-muted text-xs p-4">Loading…</p>
                            ) : fileEncoding === "binary" ? (
                                <p className="text-text-muted text-xs p-4">Binary file - use the download button to retrieve it.</p>
                            ) : (
                                <textarea
                                    className="w-full h-full bg-bg font-mono text-xs text-text-primary p-4 resize-none focus:outline-none leading-relaxed"
                                    value={fileContent}
                                    onChange={(e) => {
                                        setFileContent(e.target.value);
                                        setDirty(true);
                                    }}
                                    spellCheck={false}
                                />
                            )}
                        </div>
                    </>
                ) : (
                    <div className="flex-1 flex items-center justify-center">
                        <p className="text-text-muted text-xs">Select a file to edit</p>
                    </div>
                )}
            </div>
        </div>
        {dialog}
        </>
    );
}
