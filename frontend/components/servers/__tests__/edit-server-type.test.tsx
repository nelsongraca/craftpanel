import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {EditServerType} from "../edit-server-type";
import type {Server} from "@/lib/types";
import {selectComboboxOption} from "@/lib/test-utils";

const updateServer = vi.hoisted(() => vi.fn());

vi.mock("@/lib/generated/sdk.gen", () => ({updateServer}));

function makeServer(overrides: Record<string, unknown> = {}): Server {
    return {
        id: "s1",
        display_name: "Test Server",
        server_type: "PAPER",
        status: "STOPPED",
        ...overrides,
    } as unknown as Server;
}

describe("EditServerType", () => {
    beforeEach(() => {
        updateServer.mockReset();
    });

    it("shows the current type and allows editing when stopped", async () => {
        render(<EditServerType server={makeServer()} onSaved={vi.fn()} />);

        expect(screen.getByText("Server Type")).toBeInTheDocument();
        expect(screen.getByText("PAPER")).toBeInTheDocument();

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));

        // The type picker exposes types sourced from the backend enum.
        const combo = screen.getAllByRole("combobox")[0];
        await selectComboboxOption(user, combo, "FOLIA");
        expect(combo).toHaveTextContent("FOLIA");
    });

    it("does not allow editing while the server is running", () => {
        render(<EditServerType server={makeServer({status: "HEALTHY"})} onSaved={vi.fn()} />);

        expect(screen.queryByTitle("Edit Server Type")).not.toBeInTheDocument();
        expect(screen.getByText("Stop the server to change its type.")).toBeInTheDocument();
    });

    it("saves the new type and notifies the caller", async () => {
        updateServer.mockResolvedValue({response: {status: 204}});
        const onSaved = vi.fn();
        render(<EditServerType server={makeServer()} onSaved={onSaved} />);

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));
        await selectComboboxOption(user, screen.getAllByRole("combobox")[0], "FOLIA");

        expect(screen.getByText(/Type-exclusive settings/)).toBeInTheDocument();

        await user.click(screen.getByRole("button", {name: "Save"}));

        await waitFor(() => {
            expect(updateServer).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {server_type: "FOLIA"},
            });
        });
        expect(onSaved).toHaveBeenCalled();
    });

    it("surfaces the server's rejection message", async () => {
        updateServer.mockResolvedValue({error: {message: "Server must be STOPPED before changing its type"}});
        render(<EditServerType server={makeServer()} onSaved={vi.fn()} />);

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));
        await selectComboboxOption(user, screen.getAllByRole("combobox")[0], "FOLIA");
        await user.click(screen.getByRole("button", {name: "Save"}));

        await waitFor(() => {
            expect(screen.getByText("Server must be STOPPED before changing its type")).toBeInTheDocument();
        });
    });

    it("shows the custom-only settings for a CUSTOM server", () => {
        render(
            <EditServerType
                server={makeServer({
                    server_type: "CUSTOM",
                    custom_server_jar: "/data/custom.jar",
                    disable_healthcheck: true,
                    force_redownload: true,
                })}
                onSaved={vi.fn()}
            />,
        );

        expect(screen.getByText("Custom Server Jar")).toBeInTheDocument();
        expect(screen.getByText("/data/custom.jar")).toBeInTheDocument();
        expect(screen.getByText("Disabled")).toBeInTheDocument();
        expect(screen.getByText("On")).toBeInTheDocument();
    });

    it("hides the custom-only settings for non-CUSTOM servers", () => {
        render(<EditServerType server={makeServer()} onSaved={vi.fn()} />);

        expect(screen.queryByText("Custom Server Jar")).not.toBeInTheDocument();
        expect(screen.queryByText("Healthcheck")).not.toBeInTheDocument();
        expect(screen.queryByText("Force Redownload")).not.toBeInTheDocument();
    });

    it("prefills and updates the custom jar on an existing CUSTOM server", async () => {
        updateServer.mockResolvedValue({response: {status: 204}});
        const onSaved = vi.fn();
        render(
            <EditServerType
                server={makeServer({server_type: "CUSTOM", custom_server_jar: "/data/old.jar"})}
                onSaved={onSaved}
            />,
        );

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));

        const jarInput = screen.getByDisplayValue("/data/old.jar");
        await user.clear(jarInput);
        await user.type(jarInput, "/data/new.jar");
        await user.click(screen.getByRole("button", {name: "Save"}));

        await waitFor(() => {
            expect(updateServer).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {server_type: "CUSTOM", custom_server_jar: "/data/new.jar"},
            });
        });
        expect(onSaved).toHaveBeenCalled();
    });

    it("requires a jar when switching to CUSTOM", async () => {
        render(<EditServerType server={makeServer()} onSaved={vi.fn()} />);

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));
        await selectComboboxOption(user, screen.getAllByRole("combobox")[0], "CUSTOM");
        await user.click(screen.getByRole("button", {name: "Save"}));

        expect(await screen.findByText("Custom Server Jar is required")).toBeInTheDocument();
        expect(updateServer).not.toHaveBeenCalled();
    });

    it("sends the custom settings when switching to CUSTOM", async () => {
        updateServer.mockResolvedValue({response: {status: 204}});
        render(<EditServerType server={makeServer()} onSaved={vi.fn()} />);

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));
        await selectComboboxOption(user, screen.getAllByRole("combobox")[0], "CUSTOM");

        await user.type(
            screen.getByPlaceholderText("/data/server.jar or https://example.com/server.jar"),
            "https://example.com/server.jar",
        );
        await user.click(screen.getByRole("switch", {name: "Disable healthcheck"}));
        await user.click(screen.getByRole("switch", {name: "Force redownload on start"}));
        await user.click(screen.getByRole("button", {name: "Save"}));

        await waitFor(() => {
            expect(updateServer).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {
                    server_type: "CUSTOM",
                    custom_server_jar: "https://example.com/server.jar",
                    disable_healthcheck: true,
                    force_redownload: true,
                },
            });
        });
    });

    it("omits the custom fields when switching away from CUSTOM", async () => {
        updateServer.mockResolvedValue({response: {status: 204}});
        render(
            <EditServerType
                server={makeServer({
                    server_type: "CUSTOM",
                    custom_server_jar: "/data/custom.jar",
                    disable_healthcheck: true,
                    force_redownload: true,
                })}
                onSaved={vi.fn()}
            />,
        );

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));
        await selectComboboxOption(user, screen.getAllByRole("combobox")[0], "PAPER");

        expect(screen.queryByDisplayValue("/data/custom.jar")).not.toBeInTheDocument();

        await user.click(screen.getByRole("button", {name: "Save"}));

        await waitFor(() => {
            expect(updateServer).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {server_type: "PAPER"},
            });
        });
    });

    it("sends only the toggles that changed on an existing CUSTOM server", async () => {
        updateServer.mockResolvedValue({response: {status: 204}});
        render(
            <EditServerType
                server={makeServer({
                    server_type: "CUSTOM",
                    custom_server_jar: "/data/custom.jar",
                    disable_healthcheck: false,
                    force_redownload: false,
                })}
                onSaved={vi.fn()}
            />,
        );

        const user = userEvent.setup();
        await user.click(screen.getByTitle("Edit Server Type"));
        await user.click(screen.getByRole("switch", {name: "Disable healthcheck"}));
        await user.click(screen.getByRole("switch", {name: "Force redownload on start"}));
        await user.click(screen.getByRole("button", {name: "Save"}));

        await waitFor(() => {
            expect(updateServer).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {
                    server_type: "CUSTOM",
                    disable_healthcheck: true,
                    force_redownload: true,
                },
            });
        });
    });
});
