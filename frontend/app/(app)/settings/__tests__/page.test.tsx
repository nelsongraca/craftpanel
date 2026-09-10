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

vi.mock("@/lib/config", () => ({
    resetBrandingCache: vi.fn(),
}));

import {getSystemSettings, updateSystemSettings} from "@/lib/generated/sdk.gen";
import {resetBrandingCache} from "@/lib/config";
import SettingsPage from "../page";

const defaultSettings = {
    settings: {
        app_name: "My Panel",
        app_logo: null,
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
        console_tail_lines: 200,
        dns_domain_suffix: "mc.example.com",
        dns_zone_id: "023e105f4ecef8ad9ca31a8482d7aca9",
    },
};

const settingsWithLogo = {
    settings: {
        ...defaultSettings.settings,
        app_logo: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    },
};

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => {
        resolve = r;
    });
    return {promise, resolve};
}

function numberInput(container: HTMLElement, value: number): HTMLInputElement {
    const inputs = container.querySelectorAll<HTMLInputElement>('input[type="number"]');
    for (const input of inputs) {
        if (input.value === String(value)) return input;
    }
    throw new Error(`Number input with value ${value} not found`);
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

    it("renders form with loaded values including app name", async () => {
        const {container} = await renderWith();
        expect(screen.getByDisplayValue("My Panel")).toBeTruthy();
        expect(numberInput(container, 30)).toBeTruthy();
        expect(screen.getByDisplayValue("itzg/minecraft-server")).toBeTruthy();
    });

    it("saves app_name on submit", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        await renderWith();

        const nameInput = screen.getByDisplayValue("My Panel");
        await userEvent.setup().clear(nameInput);
        await userEvent.setup().type(nameInput, "Custom Name");
        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(updateSystemSettings).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        app_name: "Custom Name",
                    }),
                }),
            );
        });
    });

    it("shows reset to default button when app_logo is set", async () => {
        vi.mocked(getSystemSettings).mockResolvedValue({data: settingsWithLogo} as never);
        await renderWith({settings: settingsWithLogo});

        await waitFor(() => {
            expect(screen.getByText("Reset to Default")).toBeTruthy();
        });
    });

    it("shows dash placeholder when no logo is set", async () => {
        await renderWith();
        expect(screen.getByText("—")).toBeTruthy();
    });

    it("shows upload logo button", async () => {
        await renderWith();
        expect(screen.getByText("Upload Logo")).toBeTruthy();
    });

    it("shows change logo button after file input is triggered", async () => {
        const {container} = await renderWith();
        const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement;
        await userEvent.setup().upload(fileInput, new File(["fake-png"], "logo.png", {type: "image/png"}));
        await waitFor(() => {
            expect(screen.getByText("Change Logo")).toBeTruthy();
        });
    });

    it("saves settings on submit", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        const {container} = await renderWith();

        const metricInput = numberInput(container, 30);
        await userEvent.setup().clear(metricInput);
        await userEvent.setup().type(metricInput, "60");
        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(updateSystemSettings).toHaveBeenCalled();
        });
    });

    it("shows success message after save", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        vi.mocked(getSystemSettings).mockResolvedValue({data: defaultSettings} as never);
        const {container} = await renderWith();

        const metricInput = numberInput(container, 30);
        await userEvent.setup().clear(metricInput);
        await userEvent.setup().type(metricInput, "60");
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
        const {container} = await renderWith();

        const metricInput = numberInput(container, 30);
        await userEvent.setup().clear(metricInput);
        await userEvent.setup().type(metricInput, "60");
        await userEvent.setup().click(screen.getByText("Save Settings"));
        await waitFor(() => expect(screen.getByText("Settings saved.")).toBeTruthy());

        const portStartInput = numberInput(container, 25565);
        await userEvent.setup().clear(portStartInput);
        await userEvent.setup().type(portStartInput, "26000");
        expect(screen.queryByText("Settings saved.")).toBeNull();
    });

    it("sends int via API call", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        const {container} = await renderWith();

        const restartInput = numberInput(container, 3);
        await userEvent.setup().clear(restartInput);
        await userEvent.setup().type(restartInput, "5");
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

    it("calls resetBrandingCache after save", async () => {
        vi.mocked(updateSystemSettings).mockResolvedValue({error: undefined} as never);
        await renderWith();

        await userEvent.setup().click(screen.getByText("Save Settings"));

        await waitFor(() => {
            expect(resetBrandingCache).toHaveBeenCalled();
        });
    });
});
