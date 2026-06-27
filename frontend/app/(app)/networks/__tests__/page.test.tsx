import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor, fireEvent} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listNetworks: vi.fn(),
    createNetwork: vi.fn(),
    updateNetwork: vi.fn(),
    deleteNetwork: vi.fn(),
}));

vi.mock("@/app/components/PageHeader", () => ({
    default: vi.fn(
        ({title, subtitle, action}: Record<string, any>) => (
            <div>
                {title && <h1>{title}</h1>}
                {subtitle && <p>{subtitle}</p>}
                {action}
            </div>
        ),
    ),
}));

import {listNetworks, createNetwork, updateNetwork, deleteNetwork} from "@/lib/generated/sdk.gen";
import NetworksPage from "../page";

function network(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "n1",
        name: "Survival Network",
        description: "Main survival servers",
        server_count: 3,
        created_at: "2026-01-01T00:00:00Z",
        ...overrides,
    };
}

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => { resolve = r; });
    return {promise, resolve};
}

async function renderWith(mocks: { networks?: Record<string, unknown>[] } = {}) {
    const {networks: ns = [network()]} = mocks;
    vi.mocked(listNetworks).mockResolvedValue({data: ns as any});
    const ui = render(<NetworksPage/>);
    await waitFor(() => expect(screen.queryByText("Loading…")).toBeNull());
    return ui;
}

describe("NetworksPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("renders loading state initially", () => {
        const d = deferred();
        vi.mocked(listNetworks).mockReturnValue(d.promise as any);
        render(<NetworksPage/>);
        expect(screen.getByText("Loading…")).toBeTruthy();
        d.resolve({data: [network() as any]});
    });

    it("renders empty state when no networks", async () => {
        await renderWith({networks: []});
        expect(screen.getByText("No networks yet. Create one to group servers.")).toBeTruthy();
    });

    it("renders list with networks", async () => {
        await renderWith({
            networks: [
                network({id: "n1", name: "Survival", description: "Main", server_count: 3}),
                network({id: "n2", name: "Creative", description: "Build servers", server_count: 1}),
            ],
        });
        expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
        expect(screen.getAllByText("Creative").length).toBeGreaterThan(0);
    });

    it("shows server count badge in desktop table", async () => {
        await renderWith({networks: [network({server_count: 5})]});
        expect(screen.getAllByText("5").length).toBeGreaterThan(0);
    });

    it("shows description or dash", async () => {
        await renderWith({
            networks: [
                network({id: "n1", name: "With Desc", description: "Has desc"}),
                network({id: "n2", name: "No Desc", description: null}),
            ],
        });
        expect(screen.getAllByText("Has desc").length).toBeGreaterThan(0);
        expect(screen.getAllByText("—").length).toBeGreaterThan(0);
    });

    it("delete button disabled when server_count > 0", async () => {
        await renderWith({networks: [network({server_count: 2})]});
        const btns = screen.getAllByTitle("Cannot delete: has member servers");
        expect(btns.length).toBeGreaterThan(0);
        expect(btns[0]).toBeDisabled();
    });

    it("delete button enabled when server_count === 0", async () => {
        await renderWith({networks: [network({server_count: 0})]});
        const btns = screen.getAllByTitle("Delete");
        expect(btns.length).toBeGreaterThan(0);
        expect(btns[0]).not.toBeDisabled();
    });

    it("'New Network' button opens create modal", async () => {
        await renderWith({networks: []});
        await userEvent.setup().click(screen.getByText("New Network"));
        expect(screen.getByText("Create")).toBeTruthy();
    });

    it("create modal submits name and description", async () => {
        vi.mocked(createNetwork).mockResolvedValue({data: {id: "new-n"} as any, error: undefined} as any);
        vi.mocked(listNetworks).mockResolvedValue({data: []} as any);
        await renderWith({networks: []});

        await userEvent.setup().click(screen.getByText("New Network"));
        const inputs = screen.getAllByRole("textbox");
        await userEvent.setup().type(inputs[0], "NewNet");
        await userEvent.setup().click(screen.getByText("Create"));

        await waitFor(() => {
            expect(createNetwork).toHaveBeenCalled();
        });
    });

    it("edit modal opens with pre-filled data", async () => {
        await renderWith();
        const editBtns = screen.getAllByTitle("Edit");
        await userEvent.setup().click(editBtns[0]);
        expect(screen.getByDisplayValue("Survival Network")).toBeTruthy();
    });

    it("edit modal calls updateNetwork on Save", async () => {
        vi.mocked(updateNetwork).mockResolvedValue({error: undefined} as any);
        await renderWith();
        const editBtns = screen.getAllByTitle("Edit");
        await userEvent.setup().click(editBtns[0]);
        await userEvent.setup().clear(screen.getByDisplayValue("Survival Network"));
        await userEvent.setup().type(screen.getByDisplayValue(""), "Updated Net");
        await userEvent.setup().click(screen.getByText("Save"));
        await waitFor(() => {
            expect(updateNetwork).toHaveBeenCalled();
        });
    });

    it("edit modal Cancel closes without saving", async () => {
        await renderWith();
        const editBtns = screen.getAllByTitle("Edit");
        await userEvent.setup().click(editBtns[0]);
        await userEvent.setup().click(screen.getByText("Cancel"));
        expect(updateNetwork).not.toHaveBeenCalled();
    });

    it("delete modal opens on trash click", async () => {
        await renderWith({networks: [network({server_count: 0})]});
        const deleteBtns = screen.getAllByTitle("Delete");
        fireEvent.click(deleteBtns[0]);
        await waitFor(() => {
            expect(screen.getByText("Delete Network")).toBeTruthy();
        });
    });

    it("delete calls deleteNetwork and reloads", async () => {
        vi.mocked(deleteNetwork).mockResolvedValue({error: undefined} as any);
        vi.mocked(listNetworks).mockResolvedValue({data: []} as any);
        await renderWith({networks: [network({server_count: 0})]});
        const deleteBtns = screen.getAllByTitle("Delete");
        fireEvent.click(deleteBtns[0]);
        fireEvent.click(screen.getByText("Delete"));
        await waitFor(() => {
            expect(deleteNetwork).toHaveBeenCalled();
            expect(listNetworks).toHaveBeenCalledTimes(2);
        });
    });

    it("delete error shown when API fails", async () => {
        vi.mocked(deleteNetwork).mockResolvedValue({error: {message: "Cannot delete"} as any} as any);
        await renderWith({networks: [network({server_count: 0})]});
        const deleteBtns = screen.getAllByTitle("Delete");
        fireEvent.click(deleteBtns[0]);
        fireEvent.click(screen.getByText("Delete"));
        await waitFor(() => {
            expect(screen.getByText("Cannot delete")).toBeTruthy();
        });
    });

    it("renders mobile card list", async () => {
        const {container} = await renderWith({
            networks: [network({name: "MobileNet", description: "Desc", server_count: 2})],
        });
        const mobileCards = container.querySelector(".md\\:hidden");
        expect(mobileCards?.textContent).toContain("MobileNet");
        expect(mobileCards?.textContent).toContain("2 servers");
        expect(mobileCards?.textContent).toContain("Desc");
    });

    it("mobile card shows singular server count", async () => {
        const {container} = await renderWith({
            networks: [network({name: "Single", server_count: 1})],
        });
        const mobileCards = container.querySelector(".md\\:hidden");
        expect(mobileCards?.textContent).toContain("1 server");
    });
});
