import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FilesTab } from "../files-tab";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listServerFiles: vi.fn(),
    readServerFile: vi.fn(),
    deleteServerFile: vi.fn(),
    mkdirServerFile: vi.fn(),
    moveServerFile: vi.fn(),
}));

import {
    listServerFiles,
    readServerFile,
    deleteServerFile,
    mkdirServerFile,
    moveServerFile,
} from "@/lib/generated/sdk.gen";

vi.mock("@/components/ui/confirm-dialog", () => ({
    ConfirmDialog: ({
        open,
        onOpenChange,
        title,
        description,
        onConfirm,
    }: {
        open: boolean;
        onOpenChange: (open: boolean) => void;
        title: string;
        description: string;
        destructive?: boolean;
        onConfirm: () => void;
    }) =>
        open ? (
            <div data-testid="confirm-dialog">
                <div data-testid="confirm-title">{title}</div>
                <div data-testid="confirm-description">{description}</div>
                <button data-testid="confirm-cancel" onClick={() => onOpenChange(false)}>
                    Cancel
                </button>
                <button data-testid="confirm-action" onClick={onConfirm}>
                    Delete
                </button>
            </div>
        ) : null,
}));

function fileEntry(
    name = "file.txt",
    size = 1024,
    extra?: Record<string, unknown>,
) {
    return {
        name,
        is_directory: false,
        size_bytes: size,
        modified_at: "2024-01-01T00:00:00Z",
        permissions: "rw-r--r--",
        ...extra,
    };
}

function dirEntry(name = "subdir") {
    return {
        name,
        is_directory: true,
        size_bytes: 0,
        modified_at: "2024-01-01T00:00:00Z",
        permissions: "rwxr-xr-x",
    };
}

describe("FilesTab", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("renders loading state initially", () => {
        vi.mocked(listServerFiles).mockReturnValue(new Promise(() => {}));
        render(<FilesTab serverId="s1" />);
        expect(screen.getByText("Loading\u2026")).toBeInTheDocument();
    });

    it("renders file/directory listing with names after loading", async () => {
        vi.mocked(listServerFiles).mockResolvedValue({
            data: { entries: [fileEntry("notes.txt", 2048), dirEntry("plugins")] },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() => {
            expect(screen.getByText("notes.txt")).toBeInTheDocument();
        });
        expect(screen.getByText("plugins")).toBeInTheDocument();
    });

    it("empty directory shows placeholder", async () => {
        vi.mocked(listServerFiles).mockResolvedValue({
            data: { entries: [] },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() => {
            expect(screen.getByText("Empty directory")).toBeInTheDocument();
        });
    });

    it("shows error banner when listing fails", async () => {
        vi.mocked(listServerFiles).mockResolvedValue({
            error: { message: "Agent disconnected" },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() => {
            expect(screen.getByText("Agent disconnected")).toBeInTheDocument();
        });
    });

    it("clicking a directory expands it and loads children", async () => {
        const user = userEvent.setup();
        vi.mocked(listServerFiles).mockResolvedValueOnce({
            data: { entries: [dirEntry("world")] },
        } as never);
        vi.mocked(listServerFiles).mockResolvedValueOnce({
            data: { entries: [fileEntry("region.txt")] },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() => expect(screen.getByText("world")).toBeInTheDocument());

        await user.click(screen.getByText("world"));

        await waitFor(() => {
            expect(screen.getByText("region.txt")).toBeInTheDocument();
        });
        expect(listServerFiles).toHaveBeenCalledTimes(2);
        expect(listServerFiles).toHaveBeenLastCalledWith(
            expect.objectContaining({ query: { path: "/world" } }),
        );
    });

    it("collapses an expanded directory on second click", async () => {
        const user = userEvent.setup();
        vi.mocked(listServerFiles).mockResolvedValueOnce({
            data: { entries: [dirEntry("logs")] },
        } as never);
        vi.mocked(listServerFiles).mockResolvedValueOnce({
            data: { entries: [fileEntry("latest.log")] },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() => expect(screen.getByText("logs")).toBeInTheDocument());

        await user.click(screen.getByText("logs"));
        await waitFor(() => {
            expect(screen.getByText("latest.log")).toBeInTheDocument();
        });

        await user.click(screen.getByText("logs"));
        expect(screen.queryByText("latest.log")).not.toBeInTheDocument();
    });

    it("clicking a file opens it in the editor and shows path", async () => {
        const user = userEvent.setup();
        vi.mocked(listServerFiles).mockResolvedValue({
            data: { entries: [fileEntry("server.properties")] },
        } as never);
        vi.mocked(readServerFile).mockResolvedValue({
            data: { content: "max-players=20", encoding: "utf-8" },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() =>
            expect(screen.getByText("server.properties")).toBeInTheDocument(),
        );

        await user.click(screen.getByText("server.properties"));

        await waitFor(() => {
            expect(screen.getByText("/server.properties")).toBeInTheDocument();
        });
        expect(readServerFile).toHaveBeenCalledWith(
            expect.objectContaining({
                path: { id: "s1" },
                query: { path: "/server.properties" },
            }),
        );
    });

    it("file editor shows file content", async () => {
        const user = userEvent.setup();
        vi.mocked(listServerFiles).mockResolvedValue({
            data: { entries: [fileEntry("ops.json")] },
        } as never);
        vi.mocked(readServerFile).mockResolvedValue({
            data: { content: '{"ops":[]}', encoding: "utf-8" },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() => expect(screen.getByText("ops.json")).toBeInTheDocument());

        await user.click(screen.getByText("ops.json"));

        await waitFor(() => {
            expect(screen.getByDisplayValue('{"ops":[]}')).toBeInTheDocument();
        });
    });

    it("upload button triggers hidden file input click", async () => {
        vi.mocked(listServerFiles).mockResolvedValue({
            data: { entries: [] },
        } as never);
        render(<FilesTab serverId="s1" />);
        await waitFor(() =>
            expect(screen.queryByText("Loading\u2026")).not.toBeInTheDocument(),
        );

        const clickSpy = vi.spyOn(HTMLInputElement.prototype, "click");
        fireEvent.click(screen.getByTitle("Upload file"));
        expect(clickSpy).toHaveBeenCalled();
        clickSpy.mockRestore();
    });

    describe("delete", () => {
        async function setupDelete(isDir = false) {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: {
                    entries: [isDir ? dirEntry("trash") : fileEntry("old.log")],
                },
            } as never);
            vi.mocked(deleteServerFile).mockResolvedValue({ data: {} } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(
                    screen.getByText(isDir ? "trash" : "old.log"),
                ).toBeInTheDocument(),
            );
            return { user };
        }

        it("shows confirmation dialog with path", async () => {
            await setupDelete();
            fireEvent.click(screen.getByTitle("Delete"));

            await waitFor(() => {
                expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument();
            });
            expect(screen.getByTestId("confirm-title")).toHaveTextContent(
                "Delete File?",
            );
            expect(screen.getByTestId("confirm-description")).toHaveTextContent(
                "/old.log",
            );
        });

        it("calls deleteServerFile on confirm for a file", async () => {
            const { user } = await setupDelete();
            fireEvent.click(screen.getByTitle("Delete"));
            await waitFor(() =>
                expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument(),
            );

            await user.click(screen.getByTestId("confirm-action"));

            await waitFor(() => {
                expect(deleteServerFile).toHaveBeenCalledWith(
                    expect.objectContaining({
                        path: { id: "s1" },
                        query: { path: "/old.log" },
                    }),
                );
            });
        });

        it("calls deleteServerFile with recursive for a directory", async () => {
            const { user } = await setupDelete(true);
            fireEvent.click(screen.getByTitle("Delete"));
            await waitFor(() =>
                expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument(),
            );

            await user.click(screen.getByTestId("confirm-action"));

            await waitFor(() => {
                expect(deleteServerFile).toHaveBeenCalledWith(
                    expect.objectContaining({
                        path: { id: "s1" },
                        query: { path: "/trash", recursive: true },
                    }),
                );
            });
        });

        it("cancel closes confirmation and does not call API", async () => {
            const { user } = await setupDelete();
            fireEvent.click(screen.getByTitle("Delete"));
            await waitFor(() =>
                expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument(),
            );

            await user.click(screen.getByTestId("confirm-cancel"));

            await waitFor(() => {
                expect(
                    screen.queryByTestId("confirm-dialog"),
                ).not.toBeInTheDocument();
            });
            expect(deleteServerFile).not.toHaveBeenCalled();
        });

        it("shows error banner when delete fails", async () => {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("old.log")] },
            } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.getByText("old.log")).toBeInTheDocument(),
            );

            fireEvent.click(screen.getByTitle("Delete"));
            await waitFor(() =>
                expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument(),
            );

            vi.mocked(deleteServerFile).mockResolvedValue({
                error: { message: "Permission denied" },
            } as never);

            await user.click(screen.getByTestId("confirm-action"));

            await waitFor(() => {
                expect(screen.getByText("Failed to delete")).toBeInTheDocument();
            });
        });

        it("clears editor when deleting the currently open file", async () => {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("open.txt")] },
            } as never);
            vi.mocked(readServerFile).mockResolvedValue({
                data: { content: "editing", encoding: "utf-8" },
            } as never);
            vi.mocked(deleteServerFile).mockResolvedValue({ data: {} } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.getByText("open.txt")).toBeInTheDocument(),
            );

            await user.click(screen.getByText("open.txt"));
            await waitFor(() => {
                expect(screen.getByText("/open.txt")).toBeInTheDocument();
            });

            fireEvent.click(screen.getByTitle("Delete"));
            await waitFor(() =>
                expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument(),
            );
            await user.click(screen.getByTestId("confirm-action"));

            await waitFor(() => {
                expect(
                    screen.queryByText("/open.txt"),
                ).not.toBeInTheDocument();
            });
        });
    });

    describe("rename", () => {
        async function setupRename() {
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("oldname.txt"), dirEntry("stuff")] },
            } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.getByText("oldname.txt")).toBeInTheDocument(),
            );
            const renameButtons = screen.getAllByTitle("Rename");
            return { renameButtons };
        }

        it("clicking rename shows an inline input with the current name", async () => {
            const { renameButtons } = await setupRename();
            fireEvent.click(renameButtons[0]);
            const input = screen.getByDisplayValue("oldname.txt");
            expect(input).toBeInTheDocument();
        });

        it("pressing Enter calls moveServerFile with new name", async () => {
            vi.mocked(moveServerFile).mockResolvedValue({ data: {} } as never);
            const { renameButtons } = await setupRename();
            fireEvent.click(renameButtons[0]);
            const input = screen.getByDisplayValue("oldname.txt");
            fireEvent.change(input, { target: { value: "newname.txt" } });
            fireEvent.keyDown(input, { key: "Enter" });

            await waitFor(() => {
                expect(moveServerFile).toHaveBeenCalledWith(
                    expect.objectContaining({
                        path: { id: "s1" },
                        body: {
                            source_path: "/oldname.txt",
                            destination_path: "/newname.txt",
                        },
                    }),
                );
            });
        });

        it("pressing Escape closes the input without calling the API", async () => {
            vi.mocked(moveServerFile).mockResolvedValue({ data: {} } as never);
            const { renameButtons } = await setupRename();
            fireEvent.click(renameButtons[0]);
            const input = screen.getByDisplayValue("oldname.txt");
            fireEvent.keyDown(input, { key: "Escape" });

            await waitFor(() => {
                expect(
                    screen.queryByDisplayValue("oldname.txt"),
                ).not.toBeInTheDocument();
            });
            expect(moveServerFile).not.toHaveBeenCalled();
        });

        it("same name is a no-op (no API call)", async () => {
            vi.mocked(moveServerFile).mockResolvedValue({ data: {} } as never);
            const { renameButtons } = await setupRename();
            fireEvent.click(renameButtons[0]);
            const input = screen.getByDisplayValue("oldname.txt");
            fireEvent.keyDown(input, { key: "Enter" });

            await waitFor(() => {
                expect(
                    screen.queryByDisplayValue("oldname.txt"),
                ).not.toBeInTheDocument();
            });
            expect(moveServerFile).not.toHaveBeenCalled();
        });

        it("shows error banner when rename fails", async () => {
            const { renameButtons } = await setupRename();
            vi.mocked(moveServerFile).mockResolvedValue({
                error: { message: "Bad path" },
            } as never);
            fireEvent.click(renameButtons[0]);
            const input = screen.getByDisplayValue("oldname.txt");
            fireEvent.change(input, { target: { value: "bad" } });
            fireEvent.keyDown(input, { key: "Enter" });

            await waitFor(() => {
                expect(screen.getByText("Failed to rename")).toBeInTheDocument();
            });
        });

        it("blur commits the rename", async () => {
            vi.mocked(moveServerFile).mockResolvedValue({ data: {} } as never);
            const { renameButtons } = await setupRename();
            fireEvent.click(renameButtons[0]);
            const input = screen.getByDisplayValue("oldname.txt");
            fireEvent.change(input, { target: { value: "blurred.txt" } });
            fireEvent.blur(input);

            await waitFor(() => {
                expect(moveServerFile).toHaveBeenCalledWith(
                    expect.objectContaining({
                        body: {
                            source_path: "/oldname.txt",
                            destination_path: "/blurred.txt",
                        },
                    }),
                );
            });
        });
    });

    describe("mkdir", () => {
        async function setupMkdir() {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [] },
            } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.queryByText("Loading\u2026")).not.toBeInTheDocument(),
            );
            return user;
        }

        it("calls mkdirServerFile with user-provided path", async () => {
            vi.mocked(mkdirServerFile).mockResolvedValue({ data: {} } as never);
            const user = await setupMkdir();

            fireEvent.click(screen.getByTitle("New folder"));
            await user.type(screen.getByLabelText("Path (relative to /)"), "newfolder");
            await user.click(screen.getByRole("button", { name: "Create" }));

            await waitFor(() => {
                expect(mkdirServerFile).toHaveBeenCalledWith(
                    expect.objectContaining({
                        path: { id: "s1" },
                        body: { path: "/newfolder" },
                    }),
                );
            });
        });

        it("does not call API when dialog is cancelled", async () => {
            const user = await setupMkdir();

            fireEvent.click(screen.getByTitle("New folder"));
            await user.type(screen.getByLabelText("Path (relative to /)"), "newfolder");
            await user.click(screen.getByRole("button", { name: "Cancel" }));

            expect(mkdirServerFile).not.toHaveBeenCalled();
        });

        it("shows error banner when mkdir fails", async () => {
            vi.mocked(mkdirServerFile).mockResolvedValue({
                error: { message: "Exists" },
            } as never);
            const user = await setupMkdir();

            fireEvent.click(screen.getByTitle("New folder"));
            await user.type(screen.getByLabelText("Path (relative to /)"), "newfolder");
            await user.click(screen.getByRole("button", { name: "Create" }));

            await waitFor(() => {
                expect(
                    screen.getByText("Failed to create directory"),
                ).toBeInTheDocument();
            });
        });
    });

    describe("save", () => {
        async function setupEditor() {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("config.yml")] },
            } as never);
            vi.mocked(readServerFile).mockResolvedValue({
                data: { content: "setting: value", encoding: "utf-8" },
            } as never);
            globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.getByText("config.yml")).toBeInTheDocument(),
            );
            await user.click(screen.getByText("config.yml"));
            await waitFor(() =>
                expect(screen.getByDisplayValue("setting: value")).toBeInTheDocument(),
            );
            return { user };
        }

        it("is disabled when content is not dirty", async () => {
            await setupEditor();
            expect(screen.getByText("Save")).toBeDisabled();
        });

        it("sends PUT request when Save is clicked with dirty content", async () => {
            const { user } = await setupEditor();
            const textarea = screen.getByDisplayValue("setting: value");
            fireEvent.change(textarea, { target: { value: "setting: newvalue" } });

            await user.click(screen.getByText("Save"));

            await waitFor(() => {
                expect(globalThis.fetch).toHaveBeenCalledWith(
                    expect.stringContaining("/api/servers/s1/files/content"),
                    expect.objectContaining({
                        method: "PUT",
                        body: "setting: newvalue",
                    }),
                );
            });
        });

        it("shows error banner when save fails", async () => {
            const { user } = await setupEditor();
            globalThis.fetch = vi.fn().mockResolvedValue(
                new Response(null, { status: 500 }),
            );
            const textarea = screen.getByDisplayValue("setting: value");
            fireEvent.change(textarea, { target: { value: "setting: bad" } });

            await user.click(screen.getByText("Save"));

            await waitFor(() => {
                expect(screen.getByText("Failed to save file")).toBeInTheDocument();
            });
        });
    });

    describe("binary file", () => {
        it("shows download button and binary message instead of save", async () => {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("icon.png")] },
            } as never);
            vi.mocked(readServerFile).mockResolvedValue({
                data: { content: "", encoding: "binary" },
            } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.getByText("icon.png")).toBeInTheDocument(),
            );

            await user.click(screen.getByText("icon.png"));

            await waitFor(() => {
                expect(
                    screen.getByText(
                        "Binary file - use the download button to retrieve it.",
                    ),
                ).toBeInTheDocument();
            });
            expect(
                screen.getByText("Download"),
            ).toBeInTheDocument();
            expect(
                screen.queryByText("Save"),
            ).not.toBeInTheDocument();
        });
    });

    describe("failed file open", () => {
        it("clears previous file content when opening a file that fails to load", async () => {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("good.txt"), fileEntry("bad.txt")] },
            } as never);
            vi.mocked(readServerFile).mockResolvedValueOnce({
                data: { content: "previous file content", encoding: "utf-8" },
            } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() => expect(screen.getByText("good.txt")).toBeInTheDocument());

            await user.click(screen.getByText("good.txt"));
            await waitFor(() =>
                expect(screen.getByDisplayValue("previous file content")).toBeInTheDocument(),
            );

            vi.mocked(readServerFile).mockResolvedValueOnce({
                error: { message: "Failed to load file" },
            } as never);
            await user.click(screen.getByText("bad.txt"));

            await waitFor(() => {
                expect(screen.getByText("Failed to load file")).toBeInTheDocument();
            });
            expect(
                screen.queryByDisplayValue("previous file content"),
            ).not.toBeInTheDocument();
        });
    });

    describe("upload", () => {
        it("shows error banner when upload fails", async () => {
            const user = userEvent.setup();
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [] },
            } as never);
            globalThis.fetch = vi.fn().mockResolvedValue(
                new Response(null, { status: 500 }),
            );
            const { container } = render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.queryByText("Loading\u2026")).not.toBeInTheDocument(),
            );

            const fileInput = container.querySelector(
                'input[type="file"]',
            ) as HTMLInputElement;
            fireEvent.change(fileInput, {
                target: { files: [new File(["data"], "upload.txt")] },
            });

            await waitFor(() =>
                expect(screen.getByDisplayValue("/upload.txt")).toBeInTheDocument(),
            );
            await user.click(screen.getByRole("button", { name: "Upload" }));

            await waitFor(() => {
                expect(screen.getByText("Upload failed")).toBeInTheDocument();
            });
        });
    });

    describe("download", () => {
        it("renders download link for file entries", async () => {
            vi.mocked(listServerFiles).mockResolvedValue({
                data: { entries: [fileEntry("backup.zip")] },
            } as never);
            render(<FilesTab serverId="s1" />);
            await waitFor(() =>
                expect(screen.getByText("backup.zip")).toBeInTheDocument(),
            );

            const link = screen.getByTitle("Download") as HTMLAnchorElement;
            expect(link).toBeInTheDocument();
            expect(link.href).toContain(
                "/api/servers/s1/files/download?path=%2Fbackup.zip",
            );
        });
    });
});
