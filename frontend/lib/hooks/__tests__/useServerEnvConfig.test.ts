import {describe, it, expect, vi, beforeEach} from 'vitest'
import {act, renderHook, waitFor} from '@testing-library/react'
import {useServerEnvConfig, type EnvField} from '../useServerEnvConfig'

vi.mock('@/lib/generated/sdk.gen', () => ({
    getEnvVars: vi.fn(),
    replaceEnvVars: vi.fn(),
}))

import * as sdk from '@/lib/generated/sdk.gen'

const FIELDS: readonly EnvField[] = [
    {key: 'MOTD', omitIfEmpty: true},
    {key: 'PVP'},
]

// Stable reference, mirroring how the component passes module-level field constants.
const NO_FIELDS: readonly EnvField[] = []

function mockLoad(envVars: { key: string; value: string }[]) {
    vi.mocked(sdk.getEnvVars).mockResolvedValue({data: {env_vars: envVars}, error: undefined} as never)
}

async function loaded(fields: readonly EnvField[] = FIELDS, envVars = [{key: 'MOTD', value: 'hi'}, {key: 'PVP', value: 'true'}, {key: 'FOO', value: 'bar'}]) {
    mockLoad(envVars)
    const hook = renderHook(() => useServerEnvConfig('s1', fields))
    await waitFor(() => expect(hook.result.current.loading).toBe(false))
    return hook
}

describe('useServerEnvConfig', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.mocked(sdk.replaceEnvVars).mockResolvedValue({data: undefined, error: undefined} as never)
    })

    it('partitions schema-known keys into the form and the rest into extra vars', async () => {
        const {result} = await loaded()

        expect(result.current.form).toEqual({MOTD: 'hi', PVP: 'true'})
        expect(result.current.extraVars).toEqual([{key: 'FOO', value: 'bar'}])
        expect(result.current.hasExtraVars).toBe(true)
        expect(result.current.isDirty).toBe(false)
    })

    it('tracks dirtiness across field and extra-var edits, and discard reverts', async () => {
        const {result} = await loaded()

        act(() => result.current.setField('PVP', 'false'))
        expect(result.current.isDirty).toBe(true)

        act(() => result.current.discard())
        expect(result.current.form.PVP).toBe('true')
        expect(result.current.isDirty).toBe(false)

        act(() => result.current.addExtra())
        act(() => result.current.updateExtra(1, 'key', 'NEW'))
        act(() => result.current.updateExtra(1, 'value', 'v'))
        expect(result.current.isDirty).toBe(true)

        act(() => result.current.removeExtra(1))
        expect(result.current.extraVars).toHaveLength(1)
    })

    it('saves known fields in schema order (omitting blank omitIfEmpty) then the extra vars', async () => {
        const {result} = await loaded()

        act(() => {
            result.current.setField('MOTD', '')
            result.current.setField('PVP', 'false')
        })
        await act(async () => {
            await result.current.save()
        })

        expect(sdk.replaceEnvVars).toHaveBeenCalledWith({
            path: {id: 's1'},
            body: {env_vars: [{key: 'PVP', value: 'false'}, {key: 'FOO', value: 'bar'}]},
        })
        // reloads after a successful save
        expect(sdk.getEnvVars).toHaveBeenCalledTimes(2)
    })

    it('blocks save on duplicate keys without calling the API', async () => {
        const {result} = await loaded()

        act(() => result.current.updateExtra(0, 'key', 'PVP'))
        await act(async () => {
            await result.current.save()
        })

        expect(result.current.error).toBe('Duplicate env var keys')
        expect(sdk.replaceEnvVars).not.toHaveBeenCalled()
    })

    it('with no schema fields every env var is preserved as an extra var', async () => {
        const {result} = await loaded(NO_FIELDS)

        expect(result.current.form).toEqual({})
        expect(result.current.extraVars).toEqual([
            {key: 'MOTD', value: 'hi'},
            {key: 'PVP', value: 'true'},
            {key: 'FOO', value: 'bar'},
        ])

        await act(async () => {
            await result.current.save()
        })
        expect(sdk.replaceEnvVars).toHaveBeenCalledWith({
            path: {id: 's1'},
            body: {
                env_vars: [
                    {key: 'MOTD', value: 'hi'},
                    {key: 'PVP', value: 'true'},
                    {key: 'FOO', value: 'bar'},
                ],
            },
        })
    })

    it('surfaces a load error', async () => {
        vi.mocked(sdk.getEnvVars).mockResolvedValue({data: undefined, error: {message: 'boom'}} as never)
        const {result} = renderHook(() => useServerEnvConfig('s1', FIELDS))

        await waitFor(() => expect(result.current.loading).toBe(false))
        expect(result.current.error).toBe('boom')
    })
})
