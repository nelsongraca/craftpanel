"use client";

import {useEffect, useState} from "react";
import {ChevronRight, CornerLeftUp, Folder} from "lucide-react";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {TextField} from "@/components/ui/form-elements";

export interface DirectoryPickerEntry {
    name: string;
    isDirectory: boolean;
    path: string;
}

interface DirectoryPickerDialogProps {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    title: string;
    description?: string;
    confirmLabel: string;
    initialDir: string;
    initialName: string;
    loadDir: (path: string) => Promise<DirectoryPickerEntry[]>;
    validate?: (destinationPath: string) => string | null;
    onConfirm: (destinationPath: string) => void;
}

function joinPath(dir: string, name: string): string {
    return dir === "/" ? `/${name}` : `${dir}/${name}`;
}

function parentOf(path: string): string {
    return path.substring(0, path.lastIndexOf("/")) || "/";
}

function breadcrumbs(dir: string): {label: string; path: string}[] {
    const crumbs = [{label: "/", path: "/"}];
    let acc = "";
    for (const segment of dir.split("/").filter(Boolean)) {
        acc += `/${segment}`;
        crumbs.push({label: segment, path: acc});
    }
    return crumbs;
}

export function DirectoryPickerDialog({
    open,
    onOpenChange,
    title,
    description,
    confirmLabel,
    initialDir,
    initialName,
    loadDir,
    validate,
    onConfirm,
}: DirectoryPickerDialogProps) {
    const [currentDir, setCurrentDir] = useState(initialDir);
    const [entries, setEntries] = useState<DirectoryPickerEntry[]>([]);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [name, setName] = useState(initialName);

    useEffect(() => {
        if (!open) return;
        setCurrentDir(initialDir);
        setName(initialName);
        setLoadError(null);
    }, [open, initialDir, initialName]);

    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        setLoading(true);
        loadDir(currentDir)
            .then((all) => {
                if (cancelled) return;
                setEntries(all.filter((entry) => entry.isDirectory));
                setLoadError(null);
                setLoading(false);
            })
            .catch(() => {
                if (cancelled) return;
                setEntries([]);
                setLoadError("Failed to list folders");
                setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [open, currentDir, loadDir]);

    const trimmedName = name.trim();
    const destination = joinPath(currentDir, trimmedName);
    const validationError = trimmedName ? (validate?.(destination) ?? null) : "Enter a name";

    function handleConfirm() {
        if (loading || validationError) return;
        onConfirm(destination);
        onOpenChange(false);
    }

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent data-testid="directory-picker" className="sm:max-w-md">
                <DialogHeader>
                    <DialogTitle>{title}</DialogTitle>
                    <DialogDescription className="truncate font-mono text-xs">
                        {description ?? initialDir}
                    </DialogDescription>
                </DialogHeader>

                <div
                    data-testid="picker-breadcrumb"
                    className="flex items-center gap-1 overflow-x-auto border-y border-border py-1.5"
                >
                    <button
                        type="button"
                        data-testid="picker-up"
                        title="Up one level"
                        disabled={currentDir === "/"}
                        className="shrink-0 rounded p-1 text-text-muted hover:text-accent disabled:opacity-30"
                        onClick={() => setCurrentDir(parentOf(currentDir))}
                    >
                        <CornerLeftUp size={13} />
                    </button>
                    {breadcrumbs(currentDir).map((crumb, i, all) => (
                        <span key={crumb.path} className="flex shrink-0 items-center gap-1">
                            <button
                                type="button"
                                className={`rounded px-1 font-mono text-xs hover:text-accent ${
                                    i === all.length - 1 ? "text-text-primary" : "text-text-dim"
                                }`}
                                onClick={() => setCurrentDir(crumb.path)}
                            >
                                {crumb.label}
                            </button>
                            {i < all.length - 1 && <ChevronRight size={11} className="text-text-muted" />}
                        </span>
                    ))}
                </div>

                <div
                    data-testid="picker-folder-list"
                    className="max-h-60 min-h-[8rem] overflow-y-auto rounded border border-border bg-bg"
                >
                    {loading ? (
                        <p className="px-3 py-2 text-xs text-text-muted">Loading…</p>
                    ) : loadError ? (
                        <p className="px-3 py-2 text-xs text-error">{loadError}</p>
                    ) : entries.length === 0 ? (
                        <p className="px-3 py-2 text-xs text-text-muted">No subfolders</p>
                    ) : (
                        entries.map((entry) => (
                            <button
                                key={entry.path}
                                type="button"
                                className="flex w-full items-center gap-2 px-3 py-1.5 text-left font-mono text-xs text-text-dim hover:bg-surface-high hover:text-text-primary"
                                onClick={() => setCurrentDir(entry.path)}
                            >
                                <Folder size={13} className="shrink-0 text-accent" />
                                <span className="truncate">{entry.name}</span>
                            </button>
                        ))
                    )}
                </div>

                <div className="flex flex-col gap-1.5">
                    <label
                        htmlFor="directory-picker-name"
                        className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase"
                    >
                        Name
                    </label>
                    <TextField
                        id="directory-picker-name"
                        autoFocus
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === "Enter") handleConfirm();
                        }}
                    />
                    {validationError && <p className="text-xs text-error">{validationError}</p>}
                </div>

                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>
                        Cancel
                    </Button>
                    <Button disabled={loading || !!validationError} onClick={handleConfirm}>
                        {confirmLabel}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
