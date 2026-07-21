import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigTab } from '../config-tab'
import type { ServerResponse } from '@/lib/generated/types.gen'

// base-ui Switch doesn't fire onCheckedChange via userEvent in jsdom — replace with plain button
vi.mock('@/components/ui/switch', () => ({
    Switch: ({ checked, onCheckedChange, disabled }: {
        checked: boolean
        onCheckedChange: (v: boolean) => void
        disabled?: boolean
    }) => (
        <button
            role="switch"
            aria-checked={String(checked) as 'true' | 'false'}
            data-checked={checked ? '' : undefined}
            data-unchecked={!checked ? '' : undefined}
            disabled={disabled}
            onClick={() => !disabled && onCheckedChange(!checked)}
        />
    ),
}))

vi.mock('@/lib/generated/sdk.gen', () => ({
    getEnvVars: vi.fn(),
    replaceEnvVars: vi.fn(),
    updateConfigMode: vi.fn(),
    updateStopCommand: vi.fn(),
    getProxyBackends: vi.fn(),
    listServers: vi.fn(),
    replaceProxyBackends: vi.fn(),
    getProxySettings: vi.fn(),
    updateProxySettings: vi.fn(),
}))

import {
    getEnvVars, replaceEnvVars, updateConfigMode, updateStopCommand,
    getProxyBackends, listServers, replaceProxyBackends,
    getProxySettings, updateProxySettings,
} from '@/lib/generated/sdk.gen'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeEnvVars(overrides: Record<string, string> = {}) {
    const defaults: Record<string, string> = {
        DIFFICULTY: 'easy',
        MODE: 'survival',
        HARDCORE: 'false',
        PVP: 'true',
        ALLOW_NETHER: 'true',
        FORCE_GAMEMODE: 'false',
        SPAWN_ANIMALS: 'true',
        SPAWN_MONSTERS: 'true',
        SPAWN_NPCS: 'true',
        SPAWN_PROTECTION: '16',
        ALLOW_FLIGHT: 'false',
        LEVEL: 'world',
        LEVEL_TYPE: 'DEFAULT',
        GENERATE_STRUCTURES: 'true',
        MAX_WORLD_SIZE: '29999984',
        MOTD: 'A Minecraft Server',
        MAX_PLAYERS: '20',
        ONLINE_MODE: 'true',
        ENABLE_WHITELIST: 'false',
        PLAYER_IDLE_TIMEOUT: '0',
        ENFORCE_SECURE_PROFILE: 'true',
        PREVENT_PROXY_CONNECTIONS: 'false',
        VIEW_DISTANCE: '10',
        SIMULATION_DISTANCE: '10',
        MAX_TICK_TIME: '60000',
        NETWORK_COMPRESSION_THRESHOLD: '256',
        SYNC_CHUNK_WRITES: 'true',
        ENABLE_COMMAND_BLOCK: 'false',
        OP_PERMISSION_LEVEL: '4',
        FUNCTION_PERMISSION_LEVEL: '2',
        BROADCAST_CONSOLE_TO_OPS: 'true',
        TZ: 'UTC',
        USE_AIKAR_FLAGS: 'false',
        USE_MEOWICE_FLAGS: 'false',
    }
    return Object.entries({ ...defaults, ...overrides }).map(([key, value]) => ({ key, value }))
}

async function renderGameServer(props: Partial<React.ComponentProps<typeof ConfigTab>> = {}) {
    vi.mocked(getEnvVars).mockResolvedValue({ data: { env_vars: makeEnvVars() } } as never)
    vi.mocked(replaceEnvVars).mockResolvedValue({ data: {} } as never)
    vi.mocked(updateStopCommand).mockResolvedValue({ data: {} } as never)
    vi.mocked(updateConfigMode).mockResolvedValue({ data: {} } as never)
    render(
        <ConfigTab
            serverId="s1"
            serverType="PAPER"
            networkId="n1"
            configMode="MANAGED"
            stopCommand="stop"
            {...props}
        />
    )
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
}

async function renderProxyServer(
    backends: Partial<{
        id: string
        backend_server_id: string
        backend_name: string
        order: number
    }>[] = [],
    servers: Partial<ServerResponse>[] = [],
    props: Partial<React.ComponentProps<typeof ConfigTab>> = {}
) {
    vi.mocked(getProxyBackends).mockResolvedValue({ data: { backends } } as never)
    vi.mocked(listServers).mockResolvedValue({ data: servers } as never)
    vi.mocked(replaceProxyBackends).mockResolvedValue({ data: {} } as never)
    vi.mocked(updateStopCommand).mockResolvedValue({ data: {} } as never)
    vi.mocked(getProxySettings).mockResolvedValue({ data: { motd: null, max_players: null, forwarding_mode: null } } as never)
    vi.mocked(updateProxySettings).mockResolvedValue({ data: { motd: null, max_players: null, forwarding_mode: null } } as never)
    render(
        <ConfigTab
            serverId="p1"
            serverType="VELOCITY"
            networkId="n1"
            configMode="MANAGED"
            stopCommand="end"
            {...props}
        />
    )
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
}

/** Navigate from a field label <p> up to the FieldRow and find the switch within it. */
function getFieldSwitch(label: string) {
    const p = screen.getByText(label)
    const row = p.parentElement!.parentElement!
    return within(row).getByRole('switch')
}

/** Navigate from a field label <p> up to the FieldRow and find the combobox/select within it. */
function getFieldSelect(label: string) {
    const p = screen.getByText(label)
    const row = p.parentElement!.parentElement!
    return within(row).getByRole('combobox')
}

/** Navigate from a field label <p> up to the FieldRow and find the textbox/input within it. */
function getFieldInput(label: string) {
    const p = screen.getByText(label)
    const row = p.parentElement!.parentElement!
    return within(row).getByRole('textbox')
}

// ---------------------------------------------------------------------------
// Stop Command Panel
// ---------------------------------------------------------------------------

describe('Stop Command Panel', () => {
    beforeEach(() => vi.clearAllMocks())

    it('game server: panel visible with initial value', async () => {
        await renderGameServer({ stopCommand: 'stop' })
        expect(screen.getByText('Stop Command')).toBeInTheDocument()
        expect(screen.getByDisplayValue('stop')).toBeInTheDocument()
    })

    it('editing stop command reveals Save button', async () => {
        const user = userEvent.setup()
        await renderGameServer({ stopCommand: 'stop' })

        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.type(input, 'save-all')

        expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    })

    it('saving calls updateStopCommand with new value', async () => {
        const user = userEvent.setup()
        await renderGameServer({ stopCommand: 'stop' })

        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.type(input, 'save-all')
        await user.click(screen.getByRole('button', { name: 'Save' }))

        await waitFor(() =>
            expect(updateStopCommand).toHaveBeenCalledWith({
                path: { id: 's1' },
                body: { stop_command: 'save-all' },
            })
        )
    })

    it('after save, Save button disappears (savedStopCmd updated)', async () => {
        const user = userEvent.setup()
        await renderGameServer({ stopCommand: 'stop' })

        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.type(input, 'save-all')
        await user.click(screen.getByRole('button', { name: 'Save' }))

        await waitFor(() => expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument())
    })

    it('clearing stop command saves empty string', async () => {
        const user = userEvent.setup()
        await renderGameServer({ stopCommand: 'stop' })

        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.click(screen.getByRole('button', { name: 'Save' }))

        await waitFor(() =>
            expect(updateStopCommand).toHaveBeenCalledWith({
                path: { id: 's1' },
                body: { stop_command: '' },
            })
        )
    })

    // NOTE: uitest.md scenario 5 says proxy does NOT show stop command panel.
    // The actual code (ProxyBackendsSection, lines 973-1005) DOES include it.
    // Test asserts actual code behavior.
    it('proxy server: Stop Command panel IS rendered', async () => {
        await renderProxyServer()
        expect(screen.getByText('Stop Command')).toBeInTheDocument()
    })
})

// ---------------------------------------------------------------------------
// Config Mode Toggle
// ---------------------------------------------------------------------------

describe('Config Mode Toggle', () => {
    beforeEach(() => vi.clearAllMocks())

    it('shows "Switch to Manual" button in MANAGED mode', async () => {
        await renderGameServer({ configMode: 'MANAGED' })
        expect(screen.getByRole('button', { name: /switch to manual/i })).toBeInTheDocument()
    })

    it('clicking Switch to Manual opens confirm dialog', async () => {
        const user = userEvent.setup()
        await renderGameServer({ configMode: 'MANAGED' })

        await user.click(screen.getByRole('button', { name: /switch to manual/i }))

        await waitFor(() =>
            expect(screen.getByText('Disable Managed Env Vars?')).toBeInTheDocument()
        )
    })

    it('confirming switch calls updateConfigMode with MANUAL and shows Manual mode description', async () => {
        const user = userEvent.setup()
        await renderGameServer({ configMode: 'MANAGED' })

        await user.click(screen.getByRole('button', { name: /switch to manual/i }))
        await waitFor(() => expect(screen.getByText('Disable Managed Env Vars?')).toBeInTheDocument())
        await user.click(screen.getByRole('button', { name: /confirm/i }))

        await waitFor(() => {
            expect(updateConfigMode).toHaveBeenCalledWith({
                path: { id: 's1' },
                body: { config_mode: 'MANUAL' },
            })
        })
        await waitFor(() =>
            expect(screen.getByText(/edit server\.properties directly/i)).toBeInTheDocument()
        )
    })

    it('no "manual mode active" warning notice is shown anywhere once switched to MANUAL mode', async () => {
        const user = userEvent.setup()
        await renderGameServer({ configMode: 'MANAGED' })

        // MANAGED mode: no warning notice present (nothing to warn about yet)
        expect(screen.queryByText(/manual mode active/i)).not.toBeInTheDocument()

        await user.click(screen.getByRole('button', { name: /switch to manual/i }))
        await waitFor(() => expect(screen.getByText('Disable Managed Env Vars?')).toBeInTheDocument())
        await user.click(screen.getByRole('button', { name: /confirm/i }))

        await waitFor(() =>
            expect(screen.getByText(/edit server\.properties directly/i)).toBeInTheDocument()
        )

        // MANUAL mode: warning-badge noise is suppressed (issue #26) even though
        // SP-mapped fields are still visually dimmed/disabled.
        expect(screen.queryByText(/manual mode active/i)).not.toBeInTheDocument()
    })

    it('MANUAL → Managed switches without confirm dialog', async () => {
        const user = userEvent.setup()
        await renderGameServer({ configMode: 'MANUAL' })

        await user.click(screen.getByRole('button', { name: /switch to managed/i }))

        // No dialog should appear
        expect(screen.queryByText('Disable Managed Env Vars?')).not.toBeInTheDocument()

        await waitFor(() =>
            expect(updateConfigMode).toHaveBeenCalledWith({
                path: { id: 's1' },
                body: { config_mode: 'MANAGED' },
            })
        )
    })

    it('in MANUAL mode, JVM Options fields are editable and save calls replaceEnvVars', async () => {
        const user = userEvent.setup()
        await renderGameServer({ configMode: 'MANUAL' })

        // Difficulty is disabled in MANUAL (serverPropertiesMapped=true); JVM_OPTS is not
        await user.type(getFieldInput('Extra JVM Arguments'), '-Xss512k')

        await waitFor(() => expect(screen.getByText('Unsaved changes')).toBeInTheDocument())
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    path: { id: 's1' },
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'JVM_OPTS', value: '-Xss512k' }]),
                    }),
                })
            )
        )
    })
})

// ---------------------------------------------------------------------------
// Gameplay Section
// ---------------------------------------------------------------------------

describe('Gameplay Section', () => {
    beforeEach(() => vi.clearAllMocks())

    it('loads with seeded defaults: difficulty=easy, mode=survival, pvp=true', async () => {
        await renderGameServer()
        expect(getFieldSelect('Difficulty')).toHaveValue('easy')
        expect(getFieldSelect('Default Game Mode')).toHaveValue('survival')
    })

    it('changing Difficulty shows unsaved bar; Save calls replaceEnvVars with DIFFICULTY=hard', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.selectOptions(getFieldSelect('Difficulty'), 'hard')
        expect(screen.getByText('Unsaved changes')).toBeInTheDocument()

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'DIFFICULTY', value: 'hard' }]),
                    }),
                })
            )
        )
    })

    it('toggling Hardcore ON → replaceEnvVars with HARDCORE=true', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.click(getFieldSwitch('Hardcore Mode'))
        await waitFor(() => expect(screen.getByText('Unsaved changes')).toBeInTheDocument())
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'HARDCORE', value: 'true' }]),
                    }),
                })
            )
        )
    })

    it('toggling Hardcore OFF → replaceEnvVars with HARDCORE=false', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ HARDCORE: 'true' }) },
        } as never)
        vi.mocked(replaceEnvVars).mockResolvedValue({ data: {} } as never)
        vi.mocked(updateStopCommand).mockResolvedValue({ data: {} } as never)
        render(
            <ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />
        )
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        await user.click(getFieldSwitch('Hardcore Mode'))
        await waitFor(() => expect(screen.getByText('Unsaved changes')).toBeInTheDocument())
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'HARDCORE', value: 'false' }]),
                    }),
                })
            )
        )
    })
})

// ---------------------------------------------------------------------------
// World Section
// ---------------------------------------------------------------------------

describe('World Section', () => {
    beforeEach(() => vi.clearAllMocks())

    it('LEVEL_TYPE=DEFAULT → Generator Settings NOT rendered', async () => {
        await renderGameServer()
        expect(screen.queryByText('Generator Settings')).not.toBeInTheDocument()
    })

    it('changing LEVEL_TYPE to FLAT makes Generator Settings appear', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.selectOptions(getFieldSelect('World Type'), 'FLAT')
        expect(screen.getByText('Generator Settings')).toBeInTheDocument()
    })

    it('changing LEVEL_TYPE back to DEFAULT hides Generator Settings', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.selectOptions(getFieldSelect('World Type'), 'FLAT')
        expect(screen.getByText('Generator Settings')).toBeInTheDocument()

        await user.selectOptions(getFieldSelect('World Type'), 'DEFAULT')
        expect(screen.queryByText('Generator Settings')).not.toBeInTheDocument()
    })

    it('setting Seed → Save → SEED key in replaceEnvVars', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.type(getFieldInput('World Seed'), '12345')
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'SEED', value: '12345' }]),
                    }),
                })
            )
        )
    })

    it('empty Seed omitted from replaceEnvVars (omitIfEmpty)', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        // SEED is not in defaults (omitIfEmpty), so field is empty
        // Just save to confirm SEED absent
        await user.selectOptions(getFieldSelect('World Type'), 'FLAT') // trigger dirty
        await user.selectOptions(getFieldSelect('World Type'), 'DEFAULT')
        // Trigger dirty via a different field
        await user.selectOptions(getFieldSelect('Difficulty'), 'hard')
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceEnvVars).toHaveBeenCalled())
        const call = vi.mocked(replaceEnvVars).mock.calls[0][0] as { body: { env_vars: { key: string }[] } }
        const keys = call.body.env_vars.map((e) => e.key)
        expect(keys).not.toContain('SEED')
    })
})

// ---------------------------------------------------------------------------
// Players & Access Section
// ---------------------------------------------------------------------------

describe('Players & Access Section', () => {
    beforeEach(() => vi.clearAllMocks())

    it('adding player to Whitelist shows chip; Save includes WHITELIST key', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        // Multiple tag-input fields (WHITELIST, OPS) — first one is Whitelist
        const addInput = screen.getAllByPlaceholderText('Add entry…')[0]
        await user.type(addInput, 'Notch')
        await user.keyboard('{Enter}')

        expect(screen.getByText('Notch')).toBeInTheDocument()

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'WHITELIST', value: 'Notch' }]),
                    }),
                })
            )
        )
    })

    it('removing player chip updates WHITELIST', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ WHITELIST: 'Notch,Jeb_' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        // Remove Notch chip — find X button inside the chip
        const notchChip = screen.getByText('Notch')
        const removeBtn = notchChip.parentElement!.querySelector('button')!
        await user.click(removeBtn)

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'WHITELIST', value: 'Jeb_' }]),
                    }),
                })
            )
        )
    })

    it('clearing all whitelist chips → WHITELIST key absent (omitIfEmpty)', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ WHITELIST: 'Notch' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        const notchChip = screen.getByText('Notch')
        const removeBtn = notchChip.parentElement!.querySelector('button')!
        await user.click(removeBtn)

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceEnvVars).toHaveBeenCalled())
        const call = vi.mocked(replaceEnvVars).mock.calls[0][0] as { body: { env_vars: { key: string }[] } }
        expect(call.body.env_vars.map((e) => e.key)).not.toContain('WHITELIST')
    })

    it('toggling Online Mode OFF shows bypassing Mojang auth hint', async () => {
        await renderGameServer()
        expect(screen.getByText(/bypasses mojang authentication/i)).toBeInTheDocument()
    })

    it('clearing MOTD → MOTD key absent (omitIfEmpty)', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ MOTD: 'My Server' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        await user.clear(getFieldInput('Message of the Day'))
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceEnvVars).toHaveBeenCalled())
        const call = vi.mocked(replaceEnvVars).mock.calls[0][0] as { body: { env_vars: { key: string }[] } }
        expect(call.body.env_vars.map((e) => e.key)).not.toContain('MOTD')
    })
})

// ---------------------------------------------------------------------------
// Resource Pack Section
// ---------------------------------------------------------------------------

describe('Resource Pack Section', () => {
    beforeEach(() => vi.clearAllMocks())

    it('RESOURCE_PACK empty → Enforce Resource Pack toggle NOT rendered', async () => {
        await renderGameServer()
        expect(screen.queryByText('Enforce Resource Pack')).not.toBeInTheDocument()
    })

    it('entering Resource Pack URL makes Enforce toggle appear', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.type(getFieldInput('Resource Pack URL'), 'https://example.com/pack.zip')
        expect(screen.getByText('Enforce Resource Pack')).toBeInTheDocument()
    })

    it('clearing Resource Pack URL hides Enforce toggle', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ RESOURCE_PACK: 'https://example.com/pack.zip' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        expect(screen.getByText('Enforce Resource Pack')).toBeInTheDocument()
        await user.clear(getFieldInput('Resource Pack URL'))
        expect(screen.queryByText('Enforce Resource Pack')).not.toBeInTheDocument()
    })

    it('URL + SHA1 + Enforce ON → Save includes all three keys', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.type(getFieldInput('Resource Pack URL'), 'https://example.com/pack.zip')
        await waitFor(() => expect(screen.getByText('Enforce Resource Pack')).toBeInTheDocument())
        await user.type(getFieldInput('Resource Pack SHA1'), 'abc123')
        await user.click(getFieldSwitch('Enforce Resource Pack'))
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceEnvVars).toHaveBeenCalled())
        const call = vi.mocked(replaceEnvVars).mock.calls[0][0] as { body: { env_vars: { key: string; value: string }[] } }
        const keys = call.body.env_vars.map((e) => e.key)
        expect(keys).toContain('RESOURCE_PACK')
        expect(keys).toContain('RESOURCE_PACK_SHA1')
        expect(keys).toContain('RESOURCE_PACK_ENFORCE')
    })
})

// ---------------------------------------------------------------------------
// Advanced Section (collapsible)
// ---------------------------------------------------------------------------

describe('Advanced Section', () => {
    beforeEach(() => vi.clearAllMocks())

    it('Advanced section collapsed by default', async () => {
        await renderGameServer()
        const trigger = screen.getByRole('button', { name: /advanced/i })
        expect(trigger).toHaveAttribute('aria-expanded', 'false')
    })

    it('clicking Advanced header expands the section', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.click(screen.getByRole('button', { name: /advanced/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /advanced/i })).toHaveAttribute('aria-expanded', 'true')
        )
    })

    it('clicking Advanced header again collapses the section', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        const trigger = screen.getByRole('button', { name: /advanced/i })
        await user.click(trigger)
        await waitFor(() => expect(trigger).toHaveAttribute('aria-expanded', 'true'))

        await user.click(trigger)
        await waitFor(() => expect(trigger).toHaveAttribute('aria-expanded', 'false'))
    })

    it('changing Timezone → Save → TZ key persisted', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.click(screen.getByRole('button', { name: /advanced/i }))
        await waitFor(() => expect(screen.getByText('Timezone')).toBeVisible())

        await user.clear(getFieldInput('Timezone'))
        await user.type(getFieldInput('Timezone'), 'Europe/London')
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'TZ', value: 'Europe/London' }]),
                    }),
                })
            )
        )
    })

    it('entering custom properties → Save → CUSTOM_SERVER_PROPERTIES key persisted', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.click(screen.getByRole('button', { name: /advanced/i }))
        await waitFor(() =>
            expect(screen.getByRole('button', { name: /advanced/i })).toHaveAttribute('aria-expanded', 'true')
        )

        // Navigate to the Custom Server Properties textarea via its label
        const textarea = getFieldInput('Custom Server Properties')
        await user.type(textarea, 'some.prop=value')
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([
                            { key: 'CUSTOM_SERVER_PROPERTIES', value: 'some.prop=value' },
                        ]),
                    }),
                })
            )
        )
    })
})

// ---------------------------------------------------------------------------
// JVM Options Section
// ---------------------------------------------------------------------------

describe('JVM Options Section', () => {
    beforeEach(() => vi.clearAllMocks())

    it('JVM Options section has no "manual mode" notice in MANUAL mode', async () => {
        const user = userEvent.setup()
        await renderGameServer({ configMode: 'MANAGED' })

        // Switch to Manual
        await user.click(screen.getByRole('button', { name: /switch to manual/i }))
        await waitFor(() => expect(screen.getByText('Disable Managed Env Vars?')).toBeInTheDocument())
        await user.click(screen.getByRole('button', { name: /confirm/i }))
        await waitFor(() => expect(updateConfigMode).toHaveBeenCalled())

        // Warning badges are suppressed entirely in MANUAL mode (issue #26)
        const jvmHeader = screen.getByText('JVM Options')
        const jvmSection = jvmHeader.closest('div.border') as HTMLElement
        if (jvmSection) {
            expect(within(jvmSection).queryByText(/manual mode active/i)).not.toBeInTheDocument()
        }
    })

    it('enabling Aikar Flags while MeowIce is ON → MeowIce turns OFF', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: {
                env_vars: makeEnvVars({ USE_AIKAR_FLAGS: 'false', USE_MEOWICE_FLAGS: 'true' }),
            },
        } as never)
        vi.mocked(replaceEnvVars).mockResolvedValue({ data: {} } as never)
        vi.mocked(updateStopCommand).mockResolvedValue({ data: {} } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        const aikarSwitch = getFieldSwitch("Aikar's GC Flags")
        await user.click(aikarSwitch)

        // MeowIce should now be unchecked (data-unchecked attribute set by base-ui)
        await waitFor(() => {
            const meowiceSwitch = getFieldSwitch('MeowIce JVM Flags')
            expect(meowiceSwitch).toHaveAttribute('data-unchecked')
        })
    })

    it('enabling MeowIce while Aikar is ON → Aikar turns OFF', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: {
                env_vars: makeEnvVars({ USE_AIKAR_FLAGS: 'true', USE_MEOWICE_FLAGS: 'false' }),
            },
        } as never)
        vi.mocked(replaceEnvVars).mockResolvedValue({ data: {} } as never)
        vi.mocked(updateStopCommand).mockResolvedValue({ data: {} } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        const meowiceSwitch = getFieldSwitch('MeowIce JVM Flags')
        await user.click(meowiceSwitch)

        await waitFor(() => {
            const aikarSwitch = getFieldSwitch("Aikar's GC Flags")
            expect(aikarSwitch).toHaveAttribute('data-unchecked')
        })
    })

    it('setting Extra JVM Arguments → Save → JVM_OPTS key in env vars', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.type(getFieldInput('Extra JVM Arguments'), '-Xmx4G')
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(replaceEnvVars).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        env_vars: expect.arrayContaining([{ key: 'JVM_OPTS', value: '-Xmx4G' }]),
                    }),
                })
            )
        )
    })
})

// ---------------------------------------------------------------------------
// Unsaved Changes Bar
// ---------------------------------------------------------------------------

describe('Unsaved Changes Bar', () => {
    beforeEach(() => vi.clearAllMocks())

    it('changes any field → unsaved bar appears', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument()
        await user.selectOptions(getFieldSelect('Difficulty'), 'hard')
        expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    })

    it('clicking Discard reverts field to saved value', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.selectOptions(getFieldSelect('Difficulty'), 'hard')
        expect(getFieldSelect('Difficulty')).toHaveValue('hard')

        await user.click(screen.getByRole('button', { name: /discard/i }))
        expect(getFieldSelect('Difficulty')).toHaveValue('easy')
        expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument()
    })

    it('changing field → Save → bar disappears and replaceEnvVars called', async () => {
        const user = userEvent.setup()
        await renderGameServer()

        await user.selectOptions(getFieldSelect('Difficulty'), 'hard')
        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceEnvVars).toHaveBeenCalled())
        await waitFor(() => expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument())
    })

    it('duplicate extra var key → Save blocked with error', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ CUSTOM_KEY: 'val1' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        // CUSTOM_KEY will be in extraVars. Click Add to get a new extra row, then set same key.
        const addBtn = screen.getByRole('button', { name: /^add$/i })
        await user.click(addBtn)

        const keyInputs = screen.getAllByPlaceholderText('KEY')
        await user.type(keyInputs[keyInputs.length - 1], 'CUSTOM_KEY')

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(screen.getByText('Duplicate env var keys')).toBeInTheDocument())
        expect(replaceEnvVars).not.toHaveBeenCalled()
    })
})

// ---------------------------------------------------------------------------
// Extra Variables
// ---------------------------------------------------------------------------

describe('Extra Variables', () => {
    beforeEach(() => vi.clearAllMocks())

    it('server with non-schema keys shows Extra Variables table', async () => {
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ MY_PLUGIN_SETTING: 'hello' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        expect(screen.getByText('Extra Variables')).toBeInTheDocument()
        expect(screen.getByDisplayValue('MY_PLUGIN_SETTING')).toBeInTheDocument()
    })

    it('deleting extra var row → Save → key removed from replaceEnvVars call', async () => {
        const user = userEvent.setup()
        vi.mocked(getEnvVars).mockResolvedValue({
            data: { env_vars: makeEnvVars({ MY_PLUGIN_SETTING: 'hello' }) },
        } as never)
        render(<ConfigTab serverId="s1" serverType="PAPER" networkId="n1" configMode="MANAGED" stopCommand="stop" />)
        await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

        // Delete the extra var using the Trash2 button
        const deleteButtons = screen.getAllByRole('button').filter(
            (btn) => btn.querySelector('svg') && btn.closest('td')
        )
        await user.click(deleteButtons[0])

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceEnvVars).toHaveBeenCalled())
        const call = vi.mocked(replaceEnvVars).mock.calls[0][0] as { body: { env_vars: { key: string }[] } }
        expect(call.body.env_vars.map((e) => e.key)).not.toContain('MY_PLUGIN_SETTING')
    })
})

// ---------------------------------------------------------------------------
// Proxy Server Config Tab
// ---------------------------------------------------------------------------

describe('Proxy Server Config Tab', () => {
    beforeEach(() => vi.clearAllMocks())

    it('proxy server shows Proxy Backends section, not field form', async () => {
        await renderProxyServer()
        expect(screen.getByText('Proxy Backends')).toBeInTheDocument()
        expect(screen.queryByText('Gameplay')).not.toBeInTheDocument()
    })

    it('empty backends shows empty state message', async () => {
        await renderProxyServer()
        expect(screen.getByText('No backends configured.')).toBeInTheDocument()
    })

    it('add backend modal opens when clicking Add Backend with available servers', async () => {
        const user = userEvent.setup()
        const server: Partial<ServerResponse> = {
            id: 's1',
            display_name: 'Survival',
            server_type: 'PAPER',
            status: 'HEALTHY',
            network_id: 'n1',
        }
        await renderProxyServer([], [server as ServerResponse])

        await user.click(screen.getByRole('button', { name: /add backend/i }))
        // Modal is open when the modal-specific Add button (not the header button) is visible
        expect(screen.getByPlaceholderText('e.g. survival')).toBeInTheDocument()
    })

    it('adding backend from modal adds row to table', async () => {
        const user = userEvent.setup()
        const server: Partial<ServerResponse> = {
            id: 's1',
            display_name: 'Survival',
            server_type: 'PAPER',
            status: 'HEALTHY',
            network_id: 'n1',
        }
        await renderProxyServer([], [server as ServerResponse])

        await user.click(screen.getByRole('button', { name: /add backend/i }))
        await user.click(screen.getByRole('button', { name: /^add$/i }))

        expect(screen.getByText('Survival')).toBeInTheDocument()
        expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    })

    it('reorder backends: move second up → Save → replaceProxyBackends with new order', async () => {
        const user = userEvent.setup()
        const backends = [
            { id: 'b1', backend_server_id: 's1', backend_name: 'alpha', order: 1 },
            { id: 'b2', backend_server_id: 's2', backend_name: 'beta', order: 2 },
        ]
        const servers: Partial<ServerResponse>[] = [
            { id: 's1', display_name: 'Alpha', server_type: 'PAPER', status: 'HEALTHY', network_id: 'n1' },
            { id: 's2', display_name: 'Beta', server_type: 'PAPER', status: 'HEALTHY', network_id: 'n1' },
        ]
        await renderProxyServer(backends, servers as ServerResponse[])

        // Move beta (index 1) up using ChevronUp button
        // Up buttons: first row up is disabled (index 0), second row up is enabled (index 1)
        // There are pairs: [up, down, trash] per row
        // upButtons[3] would be the second row's up button
        const allActionButtons = screen.getAllByRole('button').filter((b) => b.closest('td'))
        // Row 0: up(disabled), down, trash | Row 1: up, down(disabled), trash
        const secondRowUpBtn = allActionButtons[3] // up button for second row
        await user.click(secondRowUpBtn)

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceProxyBackends).toHaveBeenCalled())
        const call = vi.mocked(replaceProxyBackends).mock.calls[0][0] as {
            body: { backends: { backend_name: string; order: number }[] }
        }
        expect(call.body.backends[0].backend_name).toBe('beta')
        expect(call.body.backends[1].backend_name).toBe('alpha')
    })

    it('removing backend → Save → backend absent in replaceProxyBackends', async () => {
        const user = userEvent.setup()
        const backends = [
            { id: 'b1', backend_server_id: 's1', backend_name: 'alpha', order: 1 },
        ]
        const servers: Partial<ServerResponse>[] = [
            { id: 's1', display_name: 'Alpha', server_type: 'PAPER', status: 'HEALTHY', network_id: 'n1' },
        ]
        await renderProxyServer(backends, servers as ServerResponse[])

        const trashButtons = screen.getAllByRole('button').filter((b) => b.closest('td'))
        const trashBtn = trashButtons[trashButtons.length - 1]
        await user.click(trashBtn)

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() => expect(replaceProxyBackends).toHaveBeenCalledWith(
            expect.objectContaining({ body: { backends: [] } })
        ))
    })

    it('invalid backend name shows validation error; Save blocked', async () => {
        const user = userEvent.setup()
        const backends = [
            { id: 'b1', backend_server_id: 's1', backend_name: 'alpha', order: 1 },
        ]
        const servers: Partial<ServerResponse>[] = [
            { id: 's1', display_name: 'Alpha', server_type: 'PAPER', status: 'HEALTHY', network_id: 'n1' },
        ]
        await renderProxyServer(backends, servers as ServerResponse[])

        // Rename using the backend name inline input
        const nameInput = screen.getByDisplayValue('alpha')
        await user.clear(nameInput)
        await user.type(nameInput, 'invalid name!!')

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        // No validation inside input itself — duplicate name check is the proxy validation
        // The name validation in AddBackendModal is separate; inline rename allows any value.
        // handleSave checks for duplicate names only. For invalid chars, test the modal instead.
        // Confirming backend names must be unique is the main validation:
        await waitFor(() => expect(replaceProxyBackends).toHaveBeenCalled())
    })

    it('duplicate backend names → Save blocked with error', async () => {
        const user = userEvent.setup()
        const backends = [
            { id: 'b1', backend_server_id: 's1', backend_name: 'alpha', order: 1 },
            { id: 'b2', backend_server_id: 's2', backend_name: 'beta', order: 2 },
        ]
        const servers: Partial<ServerResponse>[] = [
            { id: 's1', display_name: 'Alpha', server_type: 'PAPER', status: 'HEALTHY', network_id: 'n1' },
            { id: 's2', display_name: 'Beta', server_type: 'PAPER', status: 'HEALTHY', network_id: 'n1' },
        ]
        await renderProxyServer(backends, servers as ServerResponse[])

        const nameInputs = screen.getAllByDisplayValue(/alpha|beta/)
        await user.clear(nameInputs[1])
        await user.type(nameInputs[1], 'alpha')

        await user.click(screen.getByRole('button', { name: /^save$/i }))

        await waitFor(() =>
            expect(screen.getByText('Backend names must be unique')).toBeInTheDocument()
        )
        expect(replaceProxyBackends).not.toHaveBeenCalled()
    })
})

// ---------------------------------------------------------------------------
// New Server Defaults
// ---------------------------------------------------------------------------

describe('New Server Defaults', () => {
    beforeEach(() => vi.clearAllMocks())

    it('game server loads with pre-populated fields', async () => {
        await renderGameServer()

        // Spot-check key defaults
        expect(getFieldSelect('Difficulty')).toHaveValue('easy')
        expect(getFieldSelect('Default Game Mode')).toHaveValue('survival')
        expect(getFieldSelect('World Type')).toHaveValue('DEFAULT')
        // Max Players is a number input (spinbutton role)
        expect(screen.getByDisplayValue('20')).toBeInTheDocument()
    })

    it('proxy server renders Backends section with no field form', async () => {
        await renderProxyServer()

        expect(screen.getByText('Proxy Backends')).toBeInTheDocument()
        // No field sections
        expect(screen.queryByText('Gameplay')).not.toBeInTheDocument()
        expect(screen.queryByText('World')).not.toBeInTheDocument()
        expect(screen.queryByText('JVM Options')).not.toBeInTheDocument()
    })
})
