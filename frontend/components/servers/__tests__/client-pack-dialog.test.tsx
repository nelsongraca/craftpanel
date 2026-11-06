import type {ComponentProps} from "react";
import {beforeEach, describe, expect, it, vi} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {ClientPackDialog} from "../client-pack-dialog";

const {previewMock, downloadMock} = vi.hoisted(() => ({
    previewMock: vi.fn(),
    downloadMock: vi.fn(),
}));

vi.mock("@/lib/generated/sdk.gen", () => ({
    previewClientPack: previewMock,
    downloadClientPack: downloadMock,
}));

const preview = {
    server_name: "Test Server",
    pack_file_name: "test-server.mrpack",
    mc_version: "1.21.1",
    server_type: "FABRIC",
    loader: "fabric",
    loader_version: "0.20.1",
    mods: [
        {
            project_id: "sodium",
            display_name: "Sodium",
            version_id: "sodium-v1",
            version_number: "0.8.13",
            file_name: "sodium.jar",
            file_size: 2048,
        },
    ],
    skipped: [
        {
            project_id: "chunky",
            display_name: "Chunky",
            reason: "Server-side only",
        },
    ],
};

function renderDialog(overrides: Partial<ComponentProps<typeof ClientPackDialog>> = {}) {
    const onOpenChange = vi.fn();
    render(
        <ClientPackDialog
            open
            onOpenChange={onOpenChange}
            serverId="s1"
            {...overrides}
        />,
    );
    return {onOpenChange};
}

describe("ClientPackDialog", () => {
    beforeEach(() => {
        previewMock.mockReset();
        downloadMock.mockReset();
        URL.createObjectURL = vi.fn(() => "blob:pack");
        URL.revokeObjectURL = vi.fn();
    });

    it("shows the resolved pack and included/skipped mods", async () => {
        previewMock.mockResolvedValue({data: preview});

        renderDialog();

        expect(await screen.findByText("test-server.mrpack")).toBeInTheDocument();
        expect(screen.getByText("1.21.1")).toBeInTheDocument();
        expect(screen.getByText("fabric 0.20.1")).toBeInTheDocument();
        expect(screen.getByText("Sodium")).toBeInTheDocument();
        expect(screen.getByText("0.8.13")).toBeInTheDocument();
        expect(screen.getByText("Chunky")).toBeInTheDocument();
        expect(screen.getByText("Server-side only")).toBeInTheDocument();
        expect(previewMock).toHaveBeenCalledWith({path: {id: "s1"}});
    });

    it("downloads the mrpack with the preview filename", async () => {
        previewMock.mockResolvedValue({data: preview});
        const blob = new Blob(["zip"], {type: "application/x-modrinth-modpack+zip"});
        downloadMock.mockResolvedValue({data: blob});
        const user = userEvent.setup();

        renderDialog();
        await screen.findByText("test-server.mrpack");
        await user.click(screen.getByRole("button", {name: "Download .mrpack"}));

        await waitFor(() => expect(downloadMock).toHaveBeenCalledWith({path: {id: "s1"}}));
        expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
    });

    it("shows the API error and retries the preview", async () => {
        previewMock.mockResolvedValueOnce({error: {message: "None of this server's mods are required on the client"}});
        previewMock.mockResolvedValueOnce({data: preview});
        const user = userEvent.setup();

        renderDialog();

        expect(
            await screen.findByText("None of this server's mods are required on the client"),
        ).toBeInTheDocument();

        await user.click(screen.getByRole("button", {name: "Retry"}));
        expect(await screen.findByText("test-server.mrpack")).toBeInTheDocument();
        expect(previewMock).toHaveBeenCalledTimes(2);
    });
});
