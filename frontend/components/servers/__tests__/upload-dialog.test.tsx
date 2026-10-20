import type {ComponentProps} from "react";
import {beforeEach, describe, expect, it, vi} from "vitest";
import {fireEvent, render, screen, waitFor} from "@testing-library/react";
import {UploadDialog} from "../upload-dialog";
import type {UploadItem} from "@/lib/upload-drop";

const {uploadServerFilesMock} = vi.hoisted(() => ({uploadServerFilesMock: vi.fn()}));

vi.mock("@/lib/generated/sdk.gen", () => ({uploadServerFiles: uploadServerFilesMock}));

const item = (name: string, relativePath = name): UploadItem => ({
    file: new File(["abc"], name, {type: "text/plain"}),
    relativePath,
});

function renderDialog(overrides: Partial<ComponentProps<typeof UploadDialog>> = {}) {
    const onOpenChange = vi.fn();
    const onFinished = vi.fn();
    const props: ComponentProps<typeof UploadDialog> = {
        open: true,
        onOpenChange,
        serverId: "s1",
        destination: "/uploads",
        items: [item("a.txt")],
        conflicts: [],
        requiresConfirmation: false,
        onFinished,
        ...overrides,
    };
    render(<UploadDialog {...props} />);
    return {onOpenChange, onFinished, props};
}

describe("UploadDialog", () => {
    beforeEach(() => {
        uploadServerFilesMock.mockReset();
    });

    it("uploads a single new file immediately and reports success", async () => {
        uploadServerFilesMock.mockResolvedValue({
            data: {uploaded: 1, failed: 0, files: [{path: "/uploads/a.txt", success: true, size_bytes: 3}]},
        });
        const {onFinished} = renderDialog();

        await waitFor(() => expect(uploadServerFilesMock).toHaveBeenCalledTimes(1));
        const call = uploadServerFilesMock.mock.calls[0][0];
        expect(call.query).toEqual({path: "/uploads"});
        expect(call.body.files).toHaveLength(1);
        expect(call.body.files[0].name).toBe("a.txt");

        expect(await screen.findByText(/1 uploaded · 0 failed/)).toBeInTheDocument();
        expect(onFinished).toHaveBeenCalled();
    });

    it("asks before overwriting existing files and then uploads", async () => {
        uploadServerFilesMock.mockResolvedValue({
            data: {uploaded: 1, failed: 0, files: [{path: "/uploads/a.txt", success: true, size_bytes: 3}]},
        });
        renderDialog({
            conflicts: ["/uploads/a.txt"],
            requiresConfirmation: true,
        });

        expect(await screen.findByTestId("upload-conflicts")).toHaveTextContent("/uploads/a.txt");
        expect(uploadServerFilesMock).not.toHaveBeenCalled();

        fireEvent.click(screen.getByRole("button", {name: /overwrite and upload/i}));

        await waitFor(() => expect(uploadServerFilesMock).toHaveBeenCalledTimes(1));
        expect(await screen.findByText(/1 uploaded · 0 failed/)).toBeInTheDocument();
    });

    it("sends the relative path as the multipart filename", async () => {
        uploadServerFilesMock.mockResolvedValue({
            data: {uploaded: 1, failed: 0, files: [{path: "/uploads/world/a.txt", success: true, size_bytes: 3}]},
        });
        renderDialog({
            items: [item("a.txt", "world/a.txt")],
            requiresConfirmation: true,
        });

        fireEvent.click(screen.getByRole("button", {name: /^upload$/i}));

        await waitFor(() => expect(uploadServerFilesMock).toHaveBeenCalledTimes(1));
        const call = uploadServerFilesMock.mock.calls[0][0];
        expect(call.body.files[0].name).toBe("world/a.txt");
    });

    it("lists per-file failures returned in a successful response", async () => {
        uploadServerFilesMock.mockResolvedValue({
            data: {
                uploaded: 0,
                failed: 1,
                files: [{path: "/uploads/bad.txt", success: false, size_bytes: 0, error: "permission denied"}],
            },
        });
        renderDialog();

        expect(await screen.findByTestId("upload-failures")).toHaveTextContent("permission denied");
        expect(screen.getByText(/0 uploaded · 1 failed/)).toBeInTheDocument();
    });

    it("shows a transport error and refreshes the tree anyway", async () => {
        uploadServerFilesMock.mockResolvedValue({error: {message: "agent disconnected"}});
        const {onFinished} = renderDialog({
            items: [item("a.txt"), item("b.txt")],
            requiresConfirmation: true,
        });

        fireEvent.click(screen.getByRole("button", {name: /^upload$/i}));

        expect(await screen.findByText("agent disconnected")).toBeInTheDocument();
        await waitFor(() => expect(onFinished).toHaveBeenCalled());
    });
});
