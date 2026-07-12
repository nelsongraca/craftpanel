import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    getSystemSettings: vi.fn(),
    updateSystemSettings: vi.fn(),
}));

const mockAuth = vi.hoisted(() => ({
    useAuth: vi.fn(() => ({user: {permissions: ["system.settings"]}})),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: mockAuth.useAuth,
}));

vi.mock("@/app/components/PageHeader", () => ({
    default: vi.fn(
        ({title, subtitle}: { title?: string; subtitle?: string }) => (
            <div>
                {title && <h1>{title}</h1>}
                {subtitle && <p>{subtitle}</p>}
            </div>
        ),
    ),
}));

import {getSystemSettings, updateSystemSettings} from "@/lib/generated/sdk.gen";
import SettingsPage from "../page";

const defaultSettings = {
    settings: {
        metric_retention_days: 30,
        default_backup_max_count: 5,
        default_port_range_start: 25565,
        default_port_range_end: 25575,
        restart_max_attempts: 3,
        restart_window_seconds: 300,
        rate_limit_login_per_minute: 20,
        rate_limit_refresh_per_minute: 10,
        image_minecraft: "itzg/minecraft-server",
        image_proxy: "itzg/mc-proxy",
    },
};

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => { resolve = r; });
    return {promise, resolve};
}

async function renderWith(mocks: { settings?: typeof defaultSettings; permissions?: string[] } = {}) {
    const {settings: s = defaultSettings, permissions: p = ["system.settings"]} = mocks;
    mockAuth.useAuth.mockReturnValue({user: {permissions: p}});
    vi.mocked(getSystemSettings).mockResolvedValue({data: s} as never);
    const ui = render(<SettingsPage/>);
    await waitFor(() => expect(screen.queryByText("Loading…")).toBeNull());
    return ui;
}

describe("SettingsPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("renders loading state initially", () => {
        mockAuth.useAuth.mockReturnValue({user: {permissions: ["system.settings"]}});
        const d = deferred<{ data: typeof defaultSettings }>();
        vi.mocked(getSystemSettings).mockReturnValue(d.promise as never);
        render(<SettingsPage/>);
        expect(screen.getByText("Loading…")).toBeTruthy();
        d.resolve({data: defaultSettings});
    });

    it("shows permission denied when user lacks system.settings", async () => {
        mockAuth.useAuth.mockReturnValue({user: {permissions: []}});
        vi.mocked(getSystemSettings).mockResolvedValue({data: defaultSettings} as never);
        render(<SettingsPage/>);
        await waitFor(() => {
            expect(screen.getByText(/do not have permission/i)).toBeTruthy();
        });
    });

    it("renders form with loaded values", async () => {
        await renderWith();
        expect(screen.getByDisplayValue("30")).toBeTruthy();
        expect(screen.getByDisplayValue("5")).toBeTruthy();
        expect(screen.getByDisplayValue("25565")).toBeTruthy();
        expect(screen.getByDisplayValue("itzg/minecraft-server")).toBeTruthy();
    });

    it("saves settings on submit", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        await renderWith();

        await userEvent.setup().clear(screen.getByDisplayValue("30"));
        await userEvent.setup().type(screen.getByDisplayValue(""), "60");
        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(updateSystemSettings).toHaveBeenCalled();
        });
    });

    it("shows success message after save", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        vi.mocked(getSystemSettings).mockResolvedValue({data: defaultSettings} as never);
        await renderWith();

        await userEvent.setup().clear(screen.getByDisplayValue("30"));
        await userEvent.setup().type(screen.getByDisplayValue(""), "60");
        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(screen.getByText("Settings saved.")).toBeTruthy();
        });
    });

    it("shows error message on API failure", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: {message: "Validation failed"}} as never);
        await renderWith();

        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(screen.getByText("Validation failed")).toBeTruthy();
        });
    });

    it("shows Failed to load when getSystemSettings returns no data", async () => {
        mockAuth.useAuth.mockReturnValue({user: {permissions: ["system.settings"]}});
        vi.mocked(getSystemSettings).mockResolvedValue({data: undefined} as never);
        render(<SettingsPage/>);
        await waitFor(() => {
            expect(screen.getByText("Failed to load settings.")).toBeTruthy();
        });
    });

    it("'Save Settings' button shows disabled during save", async () => {
        const d = deferred<{ error: undefined }>();
        vi.mocked(updateSystemSettings).mockReturnValue(d.promise as never);
        await renderWith();

        const saveBtn = screen.getByText("Save Settings");
        await userEvent.setup().click(saveBtn);

        await waitFor(() => {
            expect(screen.getByText("Saving…")).toBeTruthy();
        });
        d.resolve({error: undefined});
    });

    it("modifying a field clears the success message", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        await renderWith();

        const metricInput = screen.getByDisplayValue("30");
        await userEvent.setup().clear(metricInput);
        await userEvent.setup().type(metricInput, "60");
        await userEvent.setup().click(screen.getByText("Save Settings"));
        await waitFor(() => expect(screen.getByText("Settings saved.")).toBeTruthy());

        const portStartInput = screen.getByDisplayValue("25565");
        await userEvent.setup().clear(portStartInput);
        await userEvent.setup().type(portStartInput, "26000");
        expect(screen.queryByText("Settings saved.")).toBeNull();
    });

    it("sends int via API call", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        await renderWith();

        await userEvent.setup().clear(screen.getByDisplayValue("3"));
        await userEvent.setup().type(screen.getByDisplayValue(""), "5");
        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(updateSystemSettings).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        restart_max_attempts: 5,
                    }),
                }),
            );
        });
    });
});
