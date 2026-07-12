import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {ModsTab} from '../mods-tab'
import type {ModResponse} from '@/lib/generated/types.gen'

// fetchModrinthVersions calls global fetch — stub it to return [] by default
vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, json: async () => []}))

vi.mock('@/lib/generated/sdk.gen', () => ({
    listMods: vi.fn(),
    addMod: vi.fn(),
    deleteMod: vi.fn(),
    updateMod: vi.fn(),
    searchMods: vi.fn(),
}))

import {listMods, addMod, deleteMod, updateMod} from '@/lib/generated/sdk.gen'

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function makeMod(overrides: Partial<ModResponse> = {}): ModResponse {
    return {
        id: 'mod-1',
        server_id: 's1',
        modrinth_project_id: 'abc123',
        display_name: 'WorldEdit',
        pin_strategy: 'LATEST',
        pinned_version_id: null,
        ...overrides,
    } as ModResponse
}

function defaultMods(): ModResponse[] {
    return [
        makeMod({id: 'mod-1', display_name: 'WorldEdit', modrinth_project_id: 'abc123', pin_strategy: 'LATEST'}),
        makeMod({id: 'mod-2', display_name: 'JEI', modrinth_project_id: 'def456', pin_strategy: 'PINNED', pinned_version_id: 'v1.20'}),
        makeMod({id: 'mod-3', display_name: 'OptiFine', modrinth_project_id: 'ghi789', pin_strategy: 'BETA'}),
    ]
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function renderModsTab(props: Partial<React.ComponentProps<typeof ModsTab>> = {}) {
    vi.mocked(listMods).mockResolvedValue({data: {mods: defaultMods()}} as never)
    return render(
        <ModsTab
            serverId="s1"
            serverType="FABRIC"
            mcVersion="1.21"
            onModsChanged={vi.fn()}
            {...props}
        />
    )
}

async function renderEmpty(props: Partial<React.ComponentProps<typeof ModsTab>> = {}) {
    vi.mocked(listMods).mockResolvedValue({data: {mods: []}} as never)
    return render(
        <ModsTab
            serverId="s1"
            serverType="FABRIC"
            mcVersion="1.21"
            onModsChanged={vi.fn()}
            {...props}
        />
    )
}

// ---------------------------------------------------------------------------
// Loading State
// ---------------------------------------------------------------------------

describe('Loading State', () => {
    beforeEach(() => vi.clearAllMocks())

    it('shows loading text while fetching mods', async () => {
        vi.mocked(listMods).mockReturnValue(new Promise(() => {
        })) as never
        render(<ModsTab serverId="s1" serverType="FABRIC" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        expect(screen.getByText('Loading mods…')).toBeInTheDocument()
    })

    it('shows loading plugins… for non-mod server type', async () => {
        vi.mocked(listMods).mockReturnValue(new Promise(() => {
        })) as never
        render(<ModsTab serverId="s1" serverType="PAPER" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        expect(screen.getByText('Loading plugins…')).toBeInTheDocument()
    })

    it('hides loading after data loads', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())
    })
})

// ---------------------------------------------------------------------------
// Empty State
// ---------------------------------------------------------------------------

describe('Empty State', () => {
    beforeEach(() => vi.clearAllMocks())

    it('shows "No mods installed" when list is empty', async () => {
        await renderEmpty()
        await waitFor(() => expect(screen.getByText('No mods installed')).toBeInTheDocument())
    })

    it('shows "No plugins installed" for non-mod server', async () => {
        vi.mocked(listMods).mockResolvedValue({data: {mods: []}} as never)
        render(<ModsTab serverId="s1" serverType="PAPER" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        await waitFor(() => expect(screen.getByText('No plugins installed')).toBeInTheDocument())
    })

    it('count is 0 in empty state', async () => {
        await renderEmpty()
        await waitFor(() => expect(screen.getByText('0 mods')).toBeInTheDocument())
    })
})

// ---------------------------------------------------------------------------
// Mod List
// ---------------------------------------------------------------------------

describe('Mod List', () => {
    beforeEach(() => vi.clearAllMocks())

    it('renders each mod display name', async () => {
        await renderModsTab()
        await waitFor(() => {
            expect(screen.getByText('WorldEdit')).toBeInTheDocument()
            expect(screen.getByText('JEI')).toBeInTheDocument()
            expect(screen.getByText('OptiFine')).toBeInTheDocument()
        })
    })

    it('renders modrinth project id for each mod', async () => {
        await renderModsTab()
        await waitFor(() => {
            expect(screen.getByText('abc123')).toBeInTheDocument()
            expect(screen.getByText('def456')).toBeInTheDocument()
            expect(screen.getByText('ghi789')).toBeInTheDocument()
        })
    })

    it('displays pin strategy label for each mod', async () => {
        await renderModsTab()
        await waitFor(() => {
            expect(screen.getByText('Latest stable')).toBeInTheDocument()
            expect(screen.getByText('Pinned: v1.20')).toBeInTheDocument()
        })
    })

    it('shows correct mod count', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.getByText('3 mods')).toBeInTheDocument())
    })

    it('shows plugins count for non-mod server', async () => {
        vi.mocked(listMods).mockResolvedValue({data: {mods: defaultMods()}} as never)
        render(<ModsTab serverId="s1" serverType="PAPER" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        await waitFor(() => expect(screen.getByText('3 plugins')).toBeInTheDocument())
    })

    it('calls listMods with server id on mount', async () => {
        await renderModsTab()
        await waitFor(() => expect(listMods).toHaveBeenCalledWith({path: {id: 's1'}}))
    })
})

// ---------------------------------------------------------------------------
// Error Handling
// ---------------------------------------------------------------------------

describe('Error Handling', () => {
    beforeEach(() => vi.clearAllMocks())

    it('shows error when listMods fails', async () => {
        vi.mocked(listMods).mockResolvedValue({error: {message: 'Server error'}} as never)
        render(<ModsTab serverId="s1" serverType="FABRIC" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        await waitFor(() => expect(screen.getByText('Failed to load mods')).toBeInTheDocument())
    })

    it('shows addMod error message', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())
        vi.mocked(addMod).mockResolvedValue({error: {message: 'Project not found'}} as never)

        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({
            data: {hits: [{project_id: 'err-proj', title: 'Err Mod', description: 'desc', author: 'dev', downloads: 1}]},
        } as never)

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /add mod/i}))
        const searchInput = screen.getByPlaceholderText('Search Modrinth…')
        await user.type(searchInput, 'worldedit')
        await user.click(screen.getByRole('button', {name: /search/i}))
        await waitFor(() => expect(screen.getByText('Err Mod')).toBeInTheDocument())
        await user.click(screen.getByText('Add'))
        const confirmBtn = screen.getByRole('button', {name: /^add$/i})
        await user.click(confirmBtn)
        await waitFor(() => expect(screen.getByText('Project not found')).toBeInTheDocument())
    })
})

// ---------------------------------------------------------------------------
// Refresh
// ---------------------------------------------------------------------------

describe('Refresh', () => {
    beforeEach(() => vi.clearAllMocks())

    it('refresh button calls listMods again', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())
        vi.mocked(listMods).mockClear()

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /refresh/i}))
        await waitFor(() => expect(listMods).toHaveBeenCalledWith({path: {id: 's1'}}))
    })
})

// ---------------------------------------------------------------------------
// Add Mod
// ---------------------------------------------------------------------------

describe('Add Mod', () => {
    beforeEach(() => vi.clearAllMocks())

    it('clicking Add Mod opens search panel', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /add mod/i}))
        expect(screen.getByPlaceholderText('Search Modrinth…')).toBeInTheDocument()
    })

    it('search calls searchMods with query', async () => {
        vi.mocked(listMods).mockResolvedValue({data: {mods: []}} as never)
        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({data: {hits: []}} as never)

        const user = userEvent.setup()
        render(<ModsTab serverId="s1" serverType="FABRIC" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        await user.click(screen.getByRole('button', {name: /add mod/i}))
        const searchInput = screen.getByPlaceholderText('Search Modrinth…')
        await user.type(searchInput, 'worldedit')
        await user.click(screen.getByRole('button', {name: /search/i}))

        await waitFor(() =>
            expect(searchMods).toHaveBeenCalledWith(
                expect.objectContaining({
                    path: {id: 's1'},
                    query: expect.objectContaining({query: 'worldedit', limit: 10}),
                })
            )
        )
    })

    it('search results show Add button for unadded mods', async () => {
        vi.mocked(listMods).mockResolvedValue({data: {mods: []}} as never)
        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({
            data: {
                hits: [
                    {project_id: 'proj1', title: 'Cool Mod', description: 'A cool mod', author: 'dev1', downloads: 5000},
                ],
            },
        } as never)

        const user = userEvent.setup()
        render(<ModsTab serverId="s1" serverType="FABRIC" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        await user.click(screen.getByRole('button', {name: /add mod/i}))
        await user.type(screen.getByPlaceholderText('Search Modrinth…'), 'cool')
        await user.click(screen.getByRole('button', {name: /search/i}))

        await waitFor(() => expect(screen.getByText('Cool Mod')).toBeInTheDocument())
        const addButtons = screen.getAllByText('Add')
        expect(addButtons.length).toBeGreaterThanOrEqual(1)
    })

    it('already-added mods show "Added" label', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({
            data: {
                hits: [
                    {project_id: 'abc123', title: 'WorldEdit', description: 'World editing tool', author: 'sk89q', downloads: 100000},
                ],
            },
        } as never)

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /add mod/i}))
        await user.type(screen.getByPlaceholderText('Search Modrinth…'), 'worldedit')
        await user.click(screen.getByRole('button', {name: /search/i}))

        await waitFor(() => expect(screen.getByText('Added')).toBeInTheDocument())
    })

    it('confirming add calls addMod and refreshes list', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({
            data: {
                hits: [
                    {project_id: 'newproj', title: 'New Mod', description: 'desc', author: 'dev', downloads: 100},
                ],
            },
        } as never)
        vi.mocked(addMod).mockResolvedValue({data: {} as never} as never)
        vi.mocked(listMods).mockClear()

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /add mod/i}))
        await user.type(screen.getByPlaceholderText('Search Modrinth…'), 'new')
        await user.click(screen.getByRole('button', {name: /search/i}))

        await waitFor(() => expect(screen.getByText('New Mod')).toBeInTheDocument())
        await user.click(screen.getByText('Add'))

        const confirmAddBtn = screen.getByRole('button', {name: /^add$/i})
        await user.click(confirmAddBtn)

        await waitFor(() => {
            expect(addMod).toHaveBeenCalledWith({
                path: {id: 's1'},
                body: {
                    modrinth_project_id: 'newproj',
                    display_name: 'New Mod',
                    pin_strategy: 'LATEST',
                    pinned_version_id: undefined,
                },
            })
        })

        await waitFor(() => expect(listMods).toHaveBeenCalled())
    })

    it('calls onModsChanged after successful add', async () => {
        const onModsChanged = vi.fn()
        await renderModsTab({onModsChanged})
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({
            data: {
                hits: [
                    {project_id: 'newproj', title: 'New Mod', description: 'desc', author: 'dev', downloads: 100},
                ],
            },
        } as never)
        vi.mocked(addMod).mockResolvedValue({data: {}} as never)

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /add mod/i}))
        await user.type(screen.getByPlaceholderText('Search Modrinth…'), 'new')
        await user.click(screen.getByRole('button', {name: /search/i}))

        await waitFor(() => expect(screen.getByText('New Mod')).toBeInTheDocument())
        await user.click(screen.getByText('Add'))

        const confirmAddBtn = screen.getByRole('button', {name: /^add$/i})
        await user.click(confirmAddBtn)

        await waitFor(() => expect(onModsChanged).toHaveBeenCalled())
    })

    it('pinned strategy shows version selector', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const {searchMods} = await import('@/lib/generated/sdk.gen')
        vi.mocked(searchMods).mockResolvedValue({
            data: {
                hits: [
                    {project_id: 'newproj', title: 'New Mod', description: 'desc', author: 'dev', downloads: 100},
                ],
            },
        } as never)

        const user = userEvent.setup()
        await user.click(screen.getByRole('button', {name: /add mod/i}))
        await user.type(screen.getByPlaceholderText('Search Modrinth…'), 'new')
        await user.click(screen.getByRole('button', {name: /search/i}))
        await waitFor(() => expect(screen.getByText('New Mod')).toBeInTheDocument())

        await user.click(screen.getByText('Add'))

        const strategySelect = screen.getByRole('combobox')
        await user.selectOptions(strategySelect, 'PINNED')

        await waitFor(() => {
            const versionInput = screen.getByPlaceholderText('Version ID')
            expect(versionInput).toBeInTheDocument()
        })
    })
})

// ---------------------------------------------------------------------------
// Remove Mod
// ---------------------------------------------------------------------------

describe('Remove Mod', () => {
    beforeEach(() => vi.clearAllMocks())

    it('clicking delete button calls deleteMod with mod id', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(deleteMod).mockResolvedValue({data: {}} as never)

        const user = userEvent.setup()
        const deleteButtons = screen.getAllByTitle('Remove mod')
        await user.click(deleteButtons[0])

        await waitFor(() =>
            expect(deleteMod).toHaveBeenCalledWith({
                path: {id: 's1', modId: 'mod-1'},
            })
        )
    })

    it('remove mod hides it from the list without refetch', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(deleteMod).mockResolvedValue({data: {}} as never)

        const user = userEvent.setup()
        const deleteButtons = screen.getAllByTitle('Remove mod')
        await user.click(deleteButtons[0])

        await waitFor(() => {
            expect(screen.queryByText('WorldEdit')).not.toBeInTheDocument()
            expect(screen.getByText('JEI')).toBeInTheDocument()
            expect(screen.getByText('OptiFine')).toBeInTheDocument()
        })
    })

    it('delete button is disabled during deletion', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(deleteMod).mockReturnValue(new Promise(() => {
        })) as never

        const user = userEvent.setup()
        const deleteButtons = screen.getAllByTitle('Remove mod')
        await user.click(deleteButtons[0])

        await waitFor(() => expect(deleteButtons[0]).toBeDisabled())
    })

    it('calls onModsChanged after successful delete', async () => {
        const onModsChanged = vi.fn()
        await renderModsTab({onModsChanged})
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(deleteMod).mockResolvedValue({data: {}} as never)

        const user = userEvent.setup()
        await user.click(screen.getAllByTitle('Remove mod')[0])

        await waitFor(() => expect(onModsChanged).toHaveBeenCalled())
    })
})

// ---------------------------------------------------------------------------
// Plugin Variant
// ---------------------------------------------------------------------------

describe('Plugin Variant', () => {
    beforeEach(() => vi.clearAllMocks())

    it('"Add Mod" button becomes "Add Plugin" for PAPER server', async () => {
        vi.mocked(listMods).mockResolvedValue({data: {mods: []}} as never)
        render(<ModsTab serverId="s1" serverType="PAPER" mcVersion="1.21" onModsChanged={vi.fn()}/>)
        await waitFor(() => expect(screen.getByText('Add Plugin')).toBeInTheDocument())
    })

    it('"Add Mod" shown for FABRIC server', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.getByText('Add Mod')).toBeInTheDocument())
    })
})

// ---------------------------------------------------------------------------
// Edit Mod
// ---------------------------------------------------------------------------

describe('Edit Mod', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, json: async () => []}))
    })

    it('clicking pin button opens edit UI with Save and Cancel', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByRole('button', {name: /^save$/i})).toBeInTheDocument()
            expect(screen.getByRole('button', {name: /cancel/i})).toBeInTheDocument()
        })
    })

    it('startEdit shows strategy selector in edit mode', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByDisplayValue('Latest stable')).toBeInTheDocument()
            expect(screen.getByRole('button', {name: /^save$/i})).toBeInTheDocument()
            expect(screen.getByRole('button', {name: /cancel/i})).toBeInTheDocument()
        })
    })

    it('startEdit on PINNED mod fetches versions from Modrinth', async () => {
        const fetchMock = vi.fn().mockResolvedValue({ok: true, json: async () => []})
        vi.stubGlobal('fetch', fetchMock)

        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[1])

        await waitFor(() => {
            expect(fetchMock).toHaveBeenCalledWith('https://api.modrinth.com/v2/project/def456/version')
        })
    })

    it('handleEditStrategyChange switches from LATEST to BETA', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByDisplayValue('Latest stable')).toBeInTheDocument()
        })

        const strategySelect = screen.getByDisplayValue('Latest stable')
        await user.selectOptions(strategySelect, 'BETA')

        await waitFor(() => {
            expect(screen.getByDisplayValue('Latest beta')).toBeInTheDocument()
        })
    })

    it('edit PINNED strategy shows version selector when versions available', async () => {
        const versions = [
            {id: 'v1', version_number: '1.0', name: '1.0', version_type: 'release', date_published: '2024-01-01'},
        ]
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, json: async () => versions}))

        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByDisplayValue('Latest stable')).toBeInTheDocument()
        })

        const strategySelect = screen.getByDisplayValue('Latest stable')
        await user.selectOptions(strategySelect, 'PINNED')

        await waitFor(() => {
            expect(screen.getByText('1.0 (release)')).toBeInTheDocument()
        })
    })

    it('saveEdit calls updateMod with correct params and refreshes list', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(updateMod).mockResolvedValue({data: {}} as never)
        vi.mocked(listMods).mockClear()

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByRole('button', {name: /^save$/i})).toBeInTheDocument()
        })

        const strategySelect = screen.getByDisplayValue('Latest stable')
        await user.selectOptions(strategySelect, 'BETA')
        await user.click(screen.getByRole('button', {name: /^save$/i}))

        await waitFor(() => {
            expect(updateMod).toHaveBeenCalledWith({
                path: {id: 's1', modId: 'mod-1'},
                body: {
                    pin_strategy: 'BETA',
                    pinned_version_id: undefined,
                },
            })
        })

        await waitFor(() => expect(listMods).toHaveBeenCalled())
    })

    it('saveEdit error shows error message', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(updateMod).mockResolvedValue({error: {message: 'Update failed'}} as never)

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByRole('button', {name: /^save$/i})).toBeInTheDocument()
        })

        await user.click(screen.getByRole('button', {name: /^save$/i}))

        await waitFor(() => {
            expect(screen.getByText('Update failed')).toBeInTheDocument()
        })
    })

    it('Save button disabled when PINNED strategy with empty version id', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByDisplayValue('Latest stable')).toBeInTheDocument()
        })

        const strategySelect = screen.getByDisplayValue('Latest stable')
        await user.selectOptions(strategySelect, 'PINNED')

        await waitFor(() => {
            expect(screen.getByPlaceholderText('Version ID')).toBeInTheDocument()
        })

        expect(screen.getByRole('button', {name: /^save$/i})).toBeDisabled()
    })

    it('cancel edit closes edit UI and restores strategy label', async () => {
        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByRole('button', {name: /cancel/i})).toBeInTheDocument()
        })

        await user.click(screen.getByRole('button', {name: /cancel/i}))

        await waitFor(() => {
            expect(screen.queryByRole('button', {name: /^save$/i})).not.toBeInTheDocument()
        })

        expect(screen.getByText('Latest stable')).toBeInTheDocument()
    })

    it('calls onModsChanged after successful saveEdit', async () => {
        const onModsChanged = vi.fn()
        await renderModsTab({onModsChanged})
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(updateMod).mockResolvedValue({data: {}} as never)

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByRole('button', {name: /^save$/i})).toBeInTheDocument()
        })

        await user.click(screen.getByRole('button', {name: /^save$/i}))

        await waitFor(() => expect(onModsChanged).toHaveBeenCalled())
    })

    it('saveEdit with PINNED strategy sends pinned_version_id', async () => {
        const versions = [
            {id: 'v1', version_number: '1.0', name: '1.0', version_type: 'release', date_published: '2024-01-01'},
        ]
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, json: async () => versions}))

        await renderModsTab()
        await waitFor(() => expect(screen.queryByText('Loading mods…')).not.toBeInTheDocument())

        vi.mocked(updateMod).mockResolvedValue({data: {}} as never)

        const user = userEvent.setup()
        const pinButtons = screen.getAllByTitle('Change pin strategy')
        await user.click(pinButtons[0])

        await waitFor(() => {
            expect(screen.getByDisplayValue('Latest stable')).toBeInTheDocument()
        })

        const strategySelect = screen.getByDisplayValue('Latest stable')
        await user.selectOptions(strategySelect, 'PINNED')

        await waitFor(() => {
            expect(screen.getByText('1.0 (release)')).toBeInTheDocument()
        })

        const versionSelect = screen.getByDisplayValue('1.0 (release)')
        await user.selectOptions(versionSelect, 'v1')

        await user.click(screen.getByRole('button', {name: /^save$/i}))

        await waitFor(() => {
            expect(updateMod).toHaveBeenCalledWith({
                path: {id: 's1', modId: 'mod-1'},
                body: {
                    pin_strategy: 'PINNED',
                    pinned_version_id: 'v1',
                },
            })
        })
    })
})
